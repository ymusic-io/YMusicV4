//! The desktop keyring (Secret Service: GNOME Keyring, KWallet) through libsecret, loaded at run
//! time as the JVM app loads it (`UserScopeKey.kt` `LinuxSecretService`): the same schema, attributes,
//! label, default collection and base64 text, so a key either app stored opens in the other.

use std::ffi::{CStr, CString, c_char, c_int, c_uint, c_void};
use std::sync::OnceLock;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use libloading::Library;

use crate::vault::KEY_LEN;

const LABEL: &CStr = c"YMusic sign-in key";
const SECRET_SEARCH_ALL: c_int = 1 << 1;
const G_IO_ERROR_CANCELLED: c_int = 19;

#[derive(Debug, thiserror::Error)]
pub enum SecretServiceError {
    /// No keyring answered (libsecret missing, no Secret Service on the bus): the JVM app then keeps
    /// the key in app.db.
    #[error("no Secret Service: {0}")]
    Unavailable(String),
    /// A keyring holds or may hold the item but did not hand it over: nothing may replace it.
    #[error("Secret Service: {0}")]
    Failed(String),
}

#[repr(C)]
struct SchemaAttribute {
    name: *const c_char,
    kind: c_int,
}

#[repr(C)]
struct Schema {
    name: *const c_char,
    flags: c_int,
    attributes: [SchemaAttribute; 32],
    reserved: c_int,
    reserved_pointers: [*mut c_void; 7],
}

#[repr(C)]
struct GError {
    domain: u32,
    code: c_int,
    message: *const c_char,
}

type HashTable = c_void;

struct Api {
    _libraries: Vec<Library>,
    lookup: unsafe extern "C" fn(*const Schema, *mut HashTable, *mut c_void, *mut *mut GError) -> *mut c_char,
    store: unsafe extern "C" fn(
        *const Schema,
        *mut HashTable,
        *const c_char,
        *const c_char,
        *const c_char,
        *mut c_void,
        *mut *mut GError,
    ) -> c_int,
    /// libsecret 0.19+.
    search: Option<
        unsafe extern "C" fn(*const Schema, *mut HashTable, c_int, *mut c_void, *mut *mut GError) -> *mut c_void,
    >,
    password_free: unsafe extern "C" fn(*mut c_char),
    hash_table_new: unsafe extern "C" fn(*const c_void, *const c_void) -> *mut HashTable,
    hash_table_insert: unsafe extern "C" fn(*mut HashTable, *const c_void, *const c_void) -> c_int,
    hash_table_unref: unsafe extern "C" fn(*mut HashTable),
    str_hash: unsafe extern "C" fn(*const c_void) -> c_uint,
    str_equal: unsafe extern "C" fn(*const c_void, *const c_void) -> c_int,
    error_free: unsafe extern "C" fn(*mut GError),
    quark_try_string: unsafe extern "C" fn(*const c_char) -> u32,
    list_free_full: unsafe extern "C" fn(*mut c_void, *const c_void),
    object_unref: unsafe extern "C" fn(*mut c_void),
}

/// Kept for the process: GLib and libsecret register GTypes and must never be unloaded.
static API: OnceLock<Result<Api, String>> = OnceLock::new();

fn open(names: &[&str]) -> Result<Library, String> {
    let mut last_error = String::new();
    for name in names {
        // SAFETY: system GLib-family libraries, whose initializers are safe to run.
        match unsafe { Library::new(name) } {
            Ok(library) => return Ok(library),
            Err(error) => last_error = error.to_string(),
        }
    }
    Err(last_error)
}

fn symbol<T: Copy>(library: &Library, name: &[u8]) -> Result<T, String> {
    // SAFETY: every caller declares `T` as the symbol's C signature.
    unsafe { library.get::<T>(name) }.map(|symbol| *symbol).map_err(|error| error.to_string())
}

fn load() -> Result<Api, String> {
    let secret = open(&["libsecret-1.so.0", "libsecret-1.so"])?;
    let glib = open(&["libglib-2.0.so.0", "libglib-2.0.so"])?;
    let gobject = open(&["libgobject-2.0.so.0", "libgobject-2.0.so"])?;
    Ok(Api {
        lookup: symbol(&secret, b"secret_password_lookupv_sync\0")?,
        store: symbol(&secret, b"secret_password_storev_sync\0")?,
        search: symbol(&secret, b"secret_password_searchv_sync\0").ok(),
        password_free: symbol(&secret, b"secret_password_free\0")?,
        hash_table_new: symbol(&glib, b"g_hash_table_new\0")?,
        hash_table_insert: symbol(&glib, b"g_hash_table_insert\0")?,
        hash_table_unref: symbol(&glib, b"g_hash_table_unref\0")?,
        str_hash: symbol(&glib, b"g_str_hash\0")?,
        str_equal: symbol(&glib, b"g_str_equal\0")?,
        error_free: symbol(&glib, b"g_error_free\0")?,
        quark_try_string: symbol(&glib, b"g_quark_try_string\0")?,
        list_free_full: symbol(&glib, b"g_list_free_full\0")?,
        object_unref: symbol(&gobject, b"g_object_unref\0")?,
        _libraries: vec![secret, glib, gobject],
    })
}

/// A libsecret call's `GError`, read and freed.
struct CallError {
    cancelled: bool,
    message: String,
}

impl Api {
    fn take_error(&self, error: *mut GError) -> Option<CallError> {
        if error.is_null() {
            return None;
        }
        // SAFETY: a GError libsecret set, freed once here.
        unsafe {
            let io_domain = (self.quark_try_string)(c"g-io-error-quark".as_ptr());
            let cancelled = io_domain != 0 && (*error).domain == io_domain && (*error).code == G_IO_ERROR_CANCELLED;
            let message = if (*error).message.is_null() {
                String::new()
            } else {
                CStr::from_ptr((*error).message).to_string_lossy().into_owned()
            };
            (self.error_free)(error);
            Some(CallError { cancelled, message })
        }
    }

    /// The JVM app's `withSchema`: schema named `service` with string attributes `service` and
    /// `account`, flags none (so libsecret also matches and stores `xdg:schema`).
    fn with_schema<T>(&self, service: &str, account: &str, block: impl FnOnce(&Schema, *mut HashTable) -> T) -> T {
        let schema_name = CString::new(service).unwrap_or_default();
        let service_key = c"service";
        let account_key = c"account";
        let service_value = CString::new(service).unwrap_or_default();
        let account_value = CString::new(account).unwrap_or_default();
        let empty = || SchemaAttribute { name: std::ptr::null(), kind: 0 };
        let mut attributes: [SchemaAttribute; 32] = std::array::from_fn(|_| empty());
        attributes[0] = SchemaAttribute { name: service_key.as_ptr(), kind: 0 };
        attributes[1] = SchemaAttribute { name: account_key.as_ptr(), kind: 0 };
        let schema = Schema {
            name: schema_name.as_ptr(),
            flags: 0,
            attributes,
            reserved: 0,
            reserved_pointers: [std::ptr::null_mut(); 7],
        };
        // SAFETY: a borrowing string table (no destroy functions) over CStrings that outlive it.
        unsafe {
            let table = (self.hash_table_new)(self.str_hash as *const c_void, self.str_equal as *const c_void);
            (self.hash_table_insert)(table, service_key.as_ptr().cast(), service_value.as_ptr().cast());
            (self.hash_table_insert)(table, account_key.as_ptr().cast(), account_value.as_ptr().cast());
            let result = block(&schema, table);
            (self.hash_table_unref)(table);
            result
        }
    }

    fn lookup(&self, service: &str, account: &str) -> Result<Option<String>, CallError> {
        self.with_schema(service, account, |schema, table| {
            let mut error = std::ptr::null_mut();
            // SAFETY: schema and table live through the call; the result is freed with
            // secret_password_free.
            let found = unsafe { (self.lookup)(schema, table, std::ptr::null_mut(), &mut error) };
            if let Some(error) = self.take_error(error) {
                return Err(error);
            }
            if found.is_null() {
                return Ok(None);
            }
            // SAFETY: a NUL-terminated string libsecret allocated.
            let value = unsafe { CStr::from_ptr(found) }.to_string_lossy().into_owned();
            // SAFETY: freed once.
            unsafe { (self.password_free)(found) };
            Ok(Some(value))
        })
    }

    /// Whether any item matches, locked ones included, without an unlock prompt. `false` when
    /// libsecret predates the search call.
    fn any_item(&self, service: &str, account: &str) -> Result<bool, CallError> {
        let Some(search) = self.search else { return Ok(false) };
        self.with_schema(service, account, |schema, table| {
            let mut error = std::ptr::null_mut();
            // SAFETY: as in lookup; the list owns references freed with g_object_unref.
            let list = unsafe { search(schema, table, SECRET_SEARCH_ALL, std::ptr::null_mut(), &mut error) };
            if let Some(error) = self.take_error(error) {
                return Err(error);
            }
            if list.is_null() {
                return Ok(false);
            }
            // SAFETY: a GList of GObject references, freed once.
            unsafe { (self.list_free_full)(list, self.object_unref as *const c_void) };
            Ok(true)
        })
    }

    fn store(&self, service: &str, account: &str, value: &str) -> Result<(), CallError> {
        let value = CString::new(value).unwrap_or_default();
        self.with_schema(service, account, |schema, table| {
            let mut error = std::ptr::null_mut();
            // SAFETY: every pointer lives through the call; a null collection is the default one.
            let ok = unsafe {
                (self.store)(
                    schema,
                    table,
                    std::ptr::null(),
                    LABEL.as_ptr(),
                    value.as_ptr(),
                    std::ptr::null_mut(),
                    &mut error,
                )
            };
            if let Some(error) = self.take_error(error) {
                return Err(error);
            }
            if ok == 0 {
                return Err(CallError { cancelled: false, message: "secret_password_storev_sync refused".into() });
            }
            Ok(())
        })
    }
}

/// The key under (`service`, `account`) in the desktop keyring, created on first use. A new key is
/// stored only when no item exists, locked ones included: libsecret's store replaces, and a lookup
/// whose unlock prompt was dismissed reads as "none". Blocking (D-Bus, maybe an unlock prompt).
pub fn secret_service_cookie_key(service: &str, account: &str) -> Result<[u8; KEY_LEN], SecretServiceError> {
    let api = API.get_or_init(load).as_ref().map_err(|error| SecretServiceError::Unavailable(error.clone()))?;
    let classify = |step: &str, error: CallError| {
        let message = format!("{step}: {}", error.message);
        if error.cancelled { SecretServiceError::Failed(message) } else { SecretServiceError::Unavailable(message) }
    };
    match api.lookup(service, account).map_err(|error| classify("secret_password_lookupv_sync", error))? {
        Some(encoded) => {
            let bytes = STANDARD
                .decode(encoded)
                .map_err(|error| SecretServiceError::Failed(format!("stored key is not base64: {error}")))?;
            bytes.try_into().map_err(|_| SecretServiceError::Failed("stored key has the wrong size".into()))
        }
        None => {
            if api.any_item(service, account).map_err(|error| classify("secret_password_searchv_sync", error))? {
                return Err(SecretServiceError::Failed("the keyring item is locked".into()));
            }
            let mut key = [0u8; KEY_LEN];
            getrandom::getrandom(&mut key).map_err(|error| SecretServiceError::Failed(error.to_string()))?;
            api.store(service, account, &STANDARD.encode(key))
                .map_err(|error| classify("secret_password_storev_sync", error))?;
            Ok(key)
        }
    }
}
