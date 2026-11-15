//! The app's view of the remote config service: feature flags, the published JS bundles, the
//! newest app build, anonymous usage stats. Plus bundle authenticity and the cached-above-embedded
//! bundle source.

mod bundle;
mod config;
mod stats;

pub use bundle::{BundleError, BundleSource, VerifiedBundle, verify_signature};
pub use config::{
    ApiIdentity, AppUpdate, BundleVersion, RelayError, RemoteConfig, RemoteConfigData, ServiceError, SkipSegment,
};
pub use stats::{PULSE_MAX_EVENTS, PulseEvent, STAT_FLUSH_INTERVAL, StatUsage};
