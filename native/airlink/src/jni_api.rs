//! JNI entry points for `com.airpointer.remote.Airlink` (Android). Errors become Java exceptions.

use std::sync::Once;

use jni::objects::{JByteArray, JClass, JObject};
use jni::sys::{jbyteArray, jint, jlong};
use jni::JNIEnv;

use crate::core;

fn key32(env: &mut JNIEnv, a: &JByteArray) -> Option<[u8; 32]> {
    let v = env.convert_byte_array(a).ok()?;
    v.try_into().ok()
}

fn throw(env: &mut JNIEnv, class: &str, msg: &str) {
    let _ = env.throw_new(class, msg);
}

/// iroh's network-interface and DNS code (netdev, n0-dns-resolver) reach Android APIs through ndk-context,
/// which must be given the JavaVM and an application Context once.
fn init_android_context(env: &mut JNIEnv, context: &JObject) {
    static INIT: Once = Once::new();
    INIT.call_once(|| {
        let (Ok(vm), Ok(global)) = (env.get_java_vm(), env.new_global_ref(context)) else { return };
        let ctx = global.as_obj().as_raw() as *mut std::ffi::c_void;
        std::mem::forget(global); // the application context lives as long as the process
        unsafe { ndk_context::initialize_android_context(vm.get_java_vm_pointer() as *mut std::ffi::c_void, ctx) };
    });
}

#[no_mangle]
pub extern "system" fn Java_com_airpointer_remote_Airlink_start(
    mut env: JNIEnv, _: JClass, context: JObject, secret: JByteArray,
) -> jlong {
    init_android_context(&mut env, &context);
    let Some(secret) = key32(&mut env, &secret) else {
        throw(&mut env, "java/lang/IllegalArgumentException", "secret must be 32 bytes");
        return 0;
    };
    match core::start(&secret, false) {
        Ok(node) => node as jlong,
        Err(e) => {
            throw(&mut env, "java/io/IOException", &e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_airpointer_remote_Airlink_id(env: JNIEnv, _: JClass, node: jlong) -> jbyteArray {
    match core::id(node as u64) {
        Some(id) => env.byte_array_from_slice(&id).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_airpointer_remote_Airlink_connect(
    mut env: JNIEnv, _: JClass, node: jlong, peer: JByteArray, timeout_ms: jint,
) -> jlong {
    let Some(peer) = key32(&mut env, &peer) else {
        throw(&mut env, "java/lang/IllegalArgumentException", "peer must be 32 bytes");
        return 0;
    };
    match core::connect(node as u64, &peer, timeout_ms.max(0) as u32) {
        Ok(pipe) => pipe as jlong,
        Err(e) => {
            throw(&mut env, "java/io/IOException", &e);
            0
        }
    }
}

/// Java InputStream semantics: count, or -1 at end of stream. Throws on timeout / error.
#[no_mangle]
pub extern "system" fn Java_com_airpointer_remote_Airlink_read(
    mut env: JNIEnv, _: JClass, pipe: jlong, buf: JByteArray, off: jint, len: jint, timeout_ms: jint,
) -> jint {
    let mut tmp = vec![0u8; len.max(0) as usize];
    let n = core::read(pipe as u64, &mut tmp, timeout_ms.max(0) as u32);
    match n {
        core::READ_EOF => -1,
        core::READ_TIMEOUT => {
            throw(&mut env, "java/net/SocketTimeoutException", "Read timed out");
            0
        }
        n if n < 0 => {
            throw(&mut env, "java/io/IOException", "Connection lost");
            0
        }
        n => {
            let bytes: &[i8] = unsafe { std::slice::from_raw_parts(tmp.as_ptr() as *const i8, n as usize) };
            if env.set_byte_array_region(&buf, off, bytes).is_err() {
                return 0;
            }
            n as jint
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_airpointer_remote_Airlink_write(
    mut env: JNIEnv, _: JClass, pipe: jlong, buf: JByteArray, off: jint, len: jint, timeout_ms: jint,
) {
    let mut tmp = vec![0i8; len.max(0) as usize];
    if env.get_byte_array_region(&buf, off, &mut tmp).is_err() {
        return;
    }
    let bytes: &[u8] = unsafe { std::slice::from_raw_parts(tmp.as_ptr() as *const u8, tmp.len()) };
    if !core::write(pipe as u64, bytes, timeout_ms.max(0) as u32) {
        throw(&mut env, "java/io/IOException", "Connection lost");
    }
}

#[no_mangle]
pub extern "system" fn Java_com_airpointer_remote_Airlink_direct(_: JNIEnv, _: JClass, pipe: jlong) -> jint {
    core::direct(pipe as u64)
}

#[no_mangle]
pub extern "system" fn Java_com_airpointer_remote_Airlink_close(_: JNIEnv, _: JClass, pipe: jlong) {
    core::close(pipe as u64)
}

#[no_mangle]
pub extern "system" fn Java_com_airpointer_remote_Airlink_stop(_: JNIEnv, _: JClass, node: jlong) {
    core::stop(node as u64)
}
