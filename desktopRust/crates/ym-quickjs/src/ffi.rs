//! Raw bindings to `quickjs/native/quickjs_native.h` in the YMusicV4 checkout.

use std::ffi::{c_char, c_int, c_void};

#[repr(C)]
pub struct QjsEngine {
    _private: [u8; 0],
}

pub const OP_OPEN: c_int = 0;
pub const OP_WRITE: c_int = 1;
pub const OP_CLOSE_WRITE: c_int = 2;
pub const OP_CLOSE_READ: c_int = 3;
pub const OP_DRAIN: c_int = 4;
pub const OP_LOG: c_int = 5;

pub type QjsInboundCb =
    unsafe extern "C" fn(user: *mut c_void, op: c_int, id: u32, data: *const u8, len: usize) -> c_int;

unsafe extern "C" {
    pub fn qjs_create(
        app_version: c_int,
        versions_json: *const c_char,
        cb: QjsInboundCb,
        user: *mut c_void,
    ) -> *mut QjsEngine;
    pub fn qjs_eval_engine(e: *mut QjsEngine, script: *const c_char, err: *mut *mut c_char) -> c_int;
    pub fn qjs_compile(
        script: *const c_char,
        filename: *const c_char,
        out_len: *mut usize,
        err: *mut *mut c_char,
    ) -> *mut u8;
    pub fn qjs_eval_engine_bc(e: *mut QjsEngine, bytecode: *const u8, len: usize, err: *mut *mut c_char) -> c_int;
    pub fn qjs_eval_sync(
        e: *mut QjsEngine,
        script: *const c_char,
        filename: *const c_char,
        is_error: *mut c_int,
    ) -> *mut c_char;
    pub fn qjs_open(e: *mut QjsEngine, id: u32, command: *const c_char) -> c_int;
    pub fn qjs_write(e: *mut QjsEngine, id: u32, data: *const u8, len: usize) -> c_int;
    pub fn qjs_close_write(e: *mut QjsEngine, id: u32, reason: *const c_char);
    pub fn qjs_close_read(e: *mut QjsEngine, id: u32);
    pub fn qjs_drain(e: *mut QjsEngine, id: u32);
    pub fn qjs_close(e: *mut QjsEngine);
    pub fn qjs_free(p: *mut c_void);
    pub fn qjs_set_mse_host(e: *mut QjsEngine, host: *mut c_void, vt: *const c_void);
}
