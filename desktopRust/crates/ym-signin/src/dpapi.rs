//! DPAPI in user scope, as the JVM app calls it (`UserScopeKey.kt` `WindowsDpapi`): no description,
//! the same entropy, `CRYPTPROTECT_UI_FORBIDDEN`. A blob either app protected opens in the other, and
//! only for this Windows user.

use windows_sys::Win32::Foundation::LocalFree;
use windows_sys::Win32::Security::Cryptography::{
    CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData,
};

use crate::vault::VaultError;

fn blob(bytes: &[u8]) -> CRYPT_INTEGER_BLOB {
    CRYPT_INTEGER_BLOB { cbData: bytes.len() as u32, pbData: bytes.as_ptr().cast_mut() }
}

fn take(function: &str, ok: i32, out: CRYPT_INTEGER_BLOB) -> Result<Vec<u8>, VaultError> {
    if ok == 0 {
        return Err(VaultError::KeyStore(format!("{function} failed: {}", std::io::Error::last_os_error())));
    }
    if out.pbData.is_null() {
        return Err(VaultError::KeyStore(format!("{function} returned no data")));
    }
    // SAFETY: DPAPI allocated `cbData` bytes at `pbData` with LocalAlloc; freed once here.
    unsafe {
        let bytes = std::slice::from_raw_parts(out.pbData, out.cbData as usize).to_vec();
        LocalFree(out.pbData.cast());
        Ok(bytes)
    }
}

/// Blocking.
pub fn dpapi_protect(data: &[u8], entropy: &[u8]) -> Result<Vec<u8>, VaultError> {
    let input = blob(data);
    let entropy = blob(entropy);
    let mut out = CRYPT_INTEGER_BLOB { cbData: 0, pbData: std::ptr::null_mut() };
    // SAFETY: the input blobs borrow live slices DPAPI only reads.
    let ok = unsafe {
        CryptProtectData(
            &input,
            std::ptr::null(),
            &entropy,
            std::ptr::null(),
            std::ptr::null(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut out,
        )
    };
    take("CryptProtectData", ok, out)
}

/// Blocking.
pub fn dpapi_unprotect(blob_bytes: &[u8], entropy: &[u8]) -> Result<Vec<u8>, VaultError> {
    let input = blob(blob_bytes);
    let entropy = blob(entropy);
    let mut out = CRYPT_INTEGER_BLOB { cbData: 0, pbData: std::ptr::null_mut() };
    // SAFETY: as in dpapi_protect.
    let ok = unsafe {
        CryptUnprotectData(
            &input,
            std::ptr::null_mut(),
            &entropy,
            std::ptr::null(),
            std::ptr::null(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut out,
        )
    };
    take("CryptUnprotectData", ok, out)
}
