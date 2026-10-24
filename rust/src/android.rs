//! JNI bridge exposed to Kotlin as `dev.kr1901.openvid.engine.NativeEngine`.
//!
//! Both entry points run synchronously on the calling JVM thread and return
//! JSON / a boolean, so no async runtime is needed at the boundary. Panics are
//! caught so they can never unwind across the FFI edge.

use jni::objects::{JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::panic;
use std::path::PathBuf;

use crate::downloader::DownloadRequest;

/// Returns a JSON `MediaInfo` on success, or `{"error": "..."}` on failure.
#[no_mangle]
pub extern "system" fn Java_dev_kr1901_openvid_engine_NativeEngine_extractFormats(
    mut env: JNIEnv,
    _this: JObject,
    url: JString,
) -> jstring {
    let outcome = panic::catch_unwind(panic::AssertUnwindSafe(|| -> Result<String, String> {
        let input: String = env.get_string(&url).map_err(|e| e.to_string())?.into();
        let client = crate::extractor::client().map_err(|e| e.to_string())?;
        let info = crate::extractor::extract(&client, &input).map_err(|e| e.to_string())?;
        serde_json::to_string(&info).map_err(|e| e.to_string())
    }));

    let payload = match outcome {
        Ok(Ok(json)) => json,
        Ok(Err(msg)) => serde_json::json!({ "error": msg }).to_string(),
        Err(_) => serde_json::json!({ "error": "internal error while extracting" }).to_string(),
    };

    match env.new_string(payload) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Downloads `url` to `dest`, reporting progress to `callback.onProgress`.
/// Returns `true` on success.
#[no_mangle]
pub extern "system" fn Java_dev_kr1901_openvid_engine_NativeEngine_download(
    mut env: JNIEnv,
    _this: JObject,
    url: JString,
    dest: JString,
    connections: jint,
    total_hint: jlong,
    callback: JObject,
) -> jboolean {
    let url: String = match env.get_string(&url) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let dest: String = match env.get_string(&dest) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    let vm = match env.get_java_vm() {
        Ok(v) => v,
        Err(_) => return JNI_FALSE,
    };
    let callback = match env.new_global_ref(callback) {
        Ok(c) => c,
        Err(_) => return JNI_FALSE,
    };

    let protocol = if url.contains(".m3u8") { "hls" } else { "direct" };
    let req = DownloadRequest {
        url,
        dest: PathBuf::from(dest),
        connections: if connections < 1 { 1 } else { connections as usize },
        protocol: protocol.to_string(),
        total_hint: if total_hint > 0 { Some(total_hint as u64) } else { None },
    };

    let outcome = panic::catch_unwind(panic::AssertUnwindSafe(|| -> anyhow::Result<()> {
        let client = crate::extractor::client()?;
        let progress = |downloaded: u64, total: u64| {
            // `get_env` returns the existing env for this thread without
            // attaching/detaching, so it is safe to call repeatedly here.
            if let Ok(mut e) = vm.get_env() {
                let _ = e.call_method(
                    &callback,
                    "onProgress",
                    "(JJ)V",
                    &[JValue::Long(downloaded as i64), JValue::Long(total as i64)],
                );
            }
        };
        crate::downloader::download(&client, &req, &progress)
    }));

    match outcome {
        Ok(Ok(())) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}
