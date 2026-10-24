//! Network request filtering, backed by Brave's `adblock` engine.
//!
//! The rule list is loaded from an Android asset by the Kotlin side and handed
//! to [`init`] once. [`is_blocked`] is then a cheap lookup, called from the
//! WebView's request interceptor for every sub-resource.

use std::sync::RwLock;

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;

static ENGINE: RwLock<Option<Engine>> = RwLock::new(None);

/// Parse `rules` (Adblock Plus syntax) and install the engine.
///
/// Safe to call again to replace the rule set — for example when a refreshed
/// list has been downloaded.
pub fn init(rules: &str) -> bool {
    let mut filter_set = FilterSet::new(false);
    filter_set.add_filter_list(rules.to_string(), ParseOptions::default());
    let engine = Engine::new_with_filter_set(filter_set);

    match ENGINE.write() {
        Ok(mut guard) => {
            *guard = Some(engine);
            true
        }
        Err(_) => false,
    }
}

pub fn is_ready() -> bool {
    ENGINE.read().map(|guard| guard.is_some()).unwrap_or(false)
}

/// Should a request for `url` (made from `source_url`, of resource `kind`) be
/// blocked?
pub fn is_blocked(url: &str, source_url: &str, kind: &str) -> bool {
    let guard = match ENGINE.read() {
        Ok(guard) => guard,
        Err(_) => return false,
    };
    let engine = match guard.as_ref() {
        Some(engine) => engine,
        None => return false,
    };

    match Request::new(url, source_url, kind, "") {
        Ok(request) => engine.check_network_request(&request).should_block(),
        Err(_) => false,
    }
}
