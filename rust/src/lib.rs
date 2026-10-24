//! openvid-core — the native engine behind the openvid-dl Android app.
//!
//! The crate builds in two shapes:
//!   * `cdylib` (feature `android`) — a `.so` loaded by the Kotlin app over JNI.
//!   * `rlib` + the `openvid-dl` binary (feature `cli`) — a desktop CLI.
//!
//! All I/O is blocking and thread-based, which keeps the JNI boundary simple:
//! every native call runs to completion on the calling (JVM) thread.

pub mod downloader;
pub mod extractor;
pub mod hls;
pub mod model;
pub mod util;

#[cfg(feature = "android")]
mod android;
