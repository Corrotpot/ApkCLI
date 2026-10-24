//! JNI surface.
//!
//! Deliberately narrow: a dozen entry points, almost all of them
//! `(op, payloadJson) -> resultJson`. Adding a capability means adding a match
//! arm in the owning module, not a new native method, a new Kotlin external
//! declaration and a new ProGuard keep rule.
//!
//! Every entry point catches panics and converts them into
//! `dev.apkcli.core.nativebridge.NativeException`. A panic that unwinds past
//! the JNI boundary is undefined behaviour and in practice kills the process
//! without a usable stack trace.

use crate::error::{CoreError, Result};
use crate::{diff, pty, sched, search, store, tokens};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

const EXCEPTION_CLASS: &str = "dev/apkcli/core/nativebridge/NativeException";

fn throw(env: &mut JNIEnv, kind: &str, message: &str) {
    // The kind prefix is parsed back out on the Kotlin side so callers can
    // branch on it without string matching the whole message.
    let _ = env.throw_new(EXCEPTION_CLASS, format!("{kind}|{message}"));
}

fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|js| js.to_string_lossy().into_owned())
        .unwrap_or_default()
}

/// Run `f`, turn its result into a Java String, turn any failure into a thrown
/// exception plus a null return.
fn run<F>(env: &mut JNIEnv, f: F) -> jstring
where
    F: FnOnce() -> Result<String>,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(s)) => match env.new_string(s) {
            Ok(j) => j.into_raw(),
            Err(e) => {
                throw(env, "internal", &format!("new_string failed: {e}"));
                std::ptr::null_mut()
            }
        },
        Ok(Err(e)) => {
            throw(env, e.kind(), &e.to_string());
            std::ptr::null_mut()
        }
        Err(p) => {
            let msg = p
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| p.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "panic".to_string());
            throw(env, "panic", &msg);
            std::ptr::null_mut()
        }
    }
}

fn run_int<F>(env: &mut JNIEnv, f: F) -> jint
where
    F: FnOnce() -> Result<jint>,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(n)) => n,
        Ok(Err(e)) => {
            throw(env, e.kind(), &e.to_string());
            -1
        }
        Err(_) => {
            throw(env, "panic", "native panic");
            -1
        }
    }
}

/* ------------------------------------------------------------- lifecycle */

#[no_mangle]
pub extern "system" fn Java_dev_apkcli_core_nativebridge_Native_nativeInit(
    _env: JNIEnv,
    _class: JClass,
    verbose: jboolean,
) {
    crate::init_logging(verbose != 0);
}

#[no_mangle]
pub extern "system" fn Java_dev_apkcli_core_nativebridge_Native_nativeVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    run(&mut env, || Ok(env!("CARGO_PKG_VERSION").to_string()))
}

/* ----------------------------------------------------------------- store */

#[no_mangle]
pub extern "system" fn Java_dev_apkcli_core_nativebridge_Native_storeOpen(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {
    let path = jstr(&mut env, &path);
    match catch_unwind(AssertUnwindSafe(|| store::Store::open(&path))) {
        Ok(Ok(st)) => Box::into_raw(Box::new(st)) as jlong,
        Ok(Err(e)) => {
            throw(&mut env, e.kind(), &e.to_string());
            0
        }
        Err(_) => {
            throw(&mut env, "panic", "store open panicked");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_apkcli_core_nativebridge_Native_storeCall(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    op: JString,
    payload: JString,
) -> jstring {
    let op = jstr(&mut env, &op);
    let payload = jstr(&mut env, &payload);
    run(&mut env, move || {
        if handle == 0 {
            return Err(CoreError::invalid("store handle is null"));
        }
        let st = unsafe { &*(handle as *const store::Store) };
        st.call(&op, &payload)
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_apkcli_core_nativebridge_Native_storeClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut store::Store));
        }
    }
}

/* -------------------------------------------------- search / diff / sched */

#[no_mangle]
pub extern "system" fn Java_dev_apkcli_core_nativebridge_Native_searchCall(
    mut env: JNIEnv,
    _class: JClass,
    op: JString,
    payload: JString,
) -> jstring {
    let op = jstr(&mut env, &op);
    let payload = jstr(&mut env, &payload);
    run(&mut env, move || search::call(&op, &payload))
}

#[no_mangle]
pub extern "system" fn Java_dev_apkcli_core_nativebridge_Native_diffCall(
    mut env: JNIEnv,
    _class: JClass,
    op: JString,
    payload: JString,
) -> jstring {
    let op = jstr(&mut env, &op);
    let payload = jstr(&mut env, &payload);
    run(&mut env, move || diff::call(&op, &payload))
}

#[no_mangle]
pub extern "system" fn Java_dev_apkcli_core_nativebridge_Native_schedCall(
    mut env: JNIEnv,
    _class: JClass,
    op: JString,
    payload: JString,
) -> jstring {
    let op = jstr(&mut env, &op);
    let payload = jstr(&mut env, &payload);
    run(&mut env, move || sched::call(&op, &payload))
}

/* ------------------------------------------------------------------- pty */

#[no_mangle]
pub extern "system" fn Java_dev_apkcli_core_nativebridge_Native_ptyCall(
    mut env: JNIEnv,
    _class: JClass,
    op: JString,
    payload: JString,
) -> jstring {
    let op = jstr(&mut env, &op);
    let payload = jstr(&mut env, &payload);
    run(&mut env, move || pty::call(&op, &payload))
}

/// Blocking read into a caller-provided array. Returns the byte count, or 0 for
/// EOF (the child exited). Call from a dedicated IO thread.
#[no_mangle]
pub extern "system" fn Java_dev_apkcli_core_nativebridge_Native_ptyRead(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    out: JByteArray,
) -> jint {
    let len = match env.get_array_length(&out) {
        Ok(n) if n > 0 => n as usize,
        Ok(_) => return 0,
        Err(e) => {
            throw(&mut env, "internal", &format!("get_array_length: {e}"));
            return -1;
        }
    };

    let mut buf = vec![0u8; len];
    let read = match catch_unwind(AssertUnwindSafe(|| pty::read_master(id, &mut buf))) {
        Ok(Ok(n)) => n,
        Ok(Err(e)) => {
            throw(&mut env, e.kind(), &e.to_string());
            return -1;
        }
        Err(_) => {
            throw(&mut env, "panic", "pty read panicked");
            return -1;
        }
    };

    if read > 0 {
        let signed =
            unsafe { std::slice::from_raw_parts(buf.as_ptr() as *const jni::sys::jbyte, read) };
        if let Err(e) = env.set_byte_array_region(&out, 0, signed) {
            throw(&mut env, "internal", &format!("set_byte_array_region: {e}"));
            return -1;
        }
    }
    read as jint
}

#[no_mangle]
pub extern "system" fn Java_dev_apkcli_core_nativebridge_Native_ptyWrite(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    data: JByteArray,
) -> jint {
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(e) => {
            throw(&mut env, "internal", &format!("convert_byte_array: {e}"));
            return -1;
        }
    };
    run_int(&mut env, move || {
        pty::write_master(id, &bytes).map(|n| n as jint)
    })
}

/* ---------------------------------------------------------------- tokens */

#[no_mangle]
pub extern "system" fn Java_dev_apkcli_core_nativebridge_Native_tokensEstimate(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
) -> jint {
    let text = jstr(&mut env, &text);
    run_int(&mut env, move || Ok(tokens::estimate(&text) as jint))
}

#[no_mangle]
pub extern "system" fn Java_dev_apkcli_core_nativebridge_Native_tokensCalibrate(
    _env: JNIEnv,
    _class: JClass,
    estimated: jint,
    reported: jint,
) {
    if estimated > 0 && reported > 0 {
        tokens::calibrate(estimated as u32, reported as u32);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_apkcli_core_nativebridge_Native_tokensClamp(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
    budget: jint,
) -> jstring {
    let text = jstr(&mut env, &text);
    run(&mut env, move || {
        let (clamped, truncated) = tokens::clamp_head_tail(&text, budget.max(0) as u32);
        Ok(serde_json::json!({ "text": clamped, "truncated": truncated }).to_string())
    })
}

/* ------------------------------------------------------------------ misc */

#[no_mangle]
pub extern "system" fn Java_dev_apkcli_core_nativebridge_Native_realpath(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let path = jstr(&mut env, &path);
    run(&mut env, move || pty::realpath(&path))
}
