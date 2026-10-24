//! JNI bridge exposed to Kotlin as `dev.kr1901.openvid.engine.NativeEngine`.
//!
//! jni 0.22 changed how native methods are declared. The first argument is now
//! an [`EnvUnowned`] (an FFI-safe wrapper around the raw env pointer) which is
//! upgraded to a `&mut Env` inside [`EnvUnowned::with_env`]. The resulting
//! [`EnvOutcome`] is mapped back to Java by an `ErrorPolicy` — here
//! `ThrowRuntimeExAndDefault`, which turns errors and panics into a Java
//! `RuntimeException` and returns the type's default value.
//!
//! `with_env` already wraps the closure in `catch_unwind`, so no manual panic
//! guarding is needed.

use jni::objects::{Global, JObject, JString};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::{jni_sig, jni_str, EnvUnowned, JValue, JavaVM};
use std::path::PathBuf;

use crate::downloader::DownloadRequest;

/// Returns a JSON `MediaInfo` on success, or `{"error": "..."}` on failure.
#[no_mangle]
pub extern "system" fn Java_dev_kr1901_openvid_engine_NativeEngine_extractFormats<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
    url: JString<'local>,
) -> JString<'local> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<_> {
            let input: String = url.to_string();
            let payload = extract_payload(&input);
            env.new_string(payload)
        })
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

fn extract_payload(input: &str) -> String {
    let result = (|| -> anyhow::Result<String> {
        let client = crate::extractor::client()?;
        let info = crate::extractor::extract(&client, input)?;
        Ok(serde_json::to_string(&info)?)
    })();

    match result {
        Ok(json) => json,
        Err(e) => serde_json::json!({ "error": e.to_string() }).to_string(),
    }
}

/// Downloads `url` to `dest`, reporting progress to `callback.onProgress`.
/// Returns `true` on success.
#[no_mangle]
pub extern "system" fn Java_dev_kr1901_openvid_engine_NativeEngine_download<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
    url: JString<'local>,
    dest: JString<'local>,
    connections: jint,
    total_hint: jlong,
    callback: JObject<'local>,
) -> jboolean {
    unowned_env
        .with_env(|env| -> jni::errors::Result<_> {
            let url: String = url.to_string();
            let dest: String = dest.to_string();
            let callback: Global<JObject<'static>> = env.new_global_ref(callback)?;

            let protocol = if url.contains(".m3u8") { "hls" } else { "direct" };
            let req = DownloadRequest {
                url,
                dest: PathBuf::from(dest),
                connections: if connections < 1 { 1 } else { connections as usize },
                protocol: protocol.to_string(),
                total_hint: if total_hint > 0 { Some(total_hint as u64) } else { None },
            };

            let ok = run_download(&req, &callback);
            Ok(if ok { JNI_TRUE } else { JNI_FALSE })
        })
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

fn run_download(req: &DownloadRequest, callback: &Global<JObject<'static>>) -> bool {
    let client = match crate::extractor::client() {
        Ok(client) => client,
        Err(_) => return false,
    };

    // Progress is only ever reported on the calling (JVM) thread. That thread is
    // already attached to the JVM, so re-attaching is cheap and pushes a fresh
    // local frame for the callback.
    let progress = |downloaded: u64, total: u64| {
        if let Ok(jvm) = JavaVM::singleton() {
            let _ = jvm.attach_current_thread(|env| -> jni::errors::Result<()> {
                env.call_method(
                    callback,
                    jni_str!("onProgress"),
                    jni_sig!("(JJ)V"),
                    &[JValue::Long(downloaded as i64), JValue::Long(total as i64)],
                )?;
                Ok(())
            });
        }
    };

    crate::downloader::download(&client, req, &progress).is_ok()
}
