//! Speed, pitch and tone (EQ, bass, virtualizer, preamp), kept in settings under the JVM app's keys
//! and applied to whichever engine plays.

use ym_i18n::{Str, t};
use ym_mse::Tone;
use ym_settings::SettingsStore;

const SPEED_KEY: &str = "player.speed";
const PITCH_KEY: &str = "player.pitch";
/// bass, virtualizer, 10 bands, preamp: preamp last, so 12-value saves still load.
const EQ_KEY: &str = "player.eq";

pub const SPEED_RANGE: (f32, f32) = (0.25, 4.0);
pub const PITCH_RANGE: (f32, f32) = (0.5, 2.0);
/// Band, bass and preamp gain, dB.
pub const GAIN_RANGE: (f32, f32) = (-12.0, 12.0);
pub const VIRTUALIZER_RANGE: (f32, f32) = (0.5, 2.0);

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct AudioEffects {
    pub speed: f32,
    pub pitch: f32,
    pub tone: Tone,
}

impl Default for AudioEffects {
    fn default() -> Self {
        Self { speed: 1.0, pitch: 1.0, tone: Tone::default() }
    }
}

impl AudioEffects {
    pub fn load(settings: &dyn SettingsStore) -> Self {
        let number = |key: &str| settings.get_raw(key).ok().flatten().and_then(|raw| raw.parse::<f32>().ok());
        let mut effects = Self::default();
        if let Some(speed) = number(SPEED_KEY) {
            effects.speed = speed.clamp(SPEED_RANGE.0, SPEED_RANGE.1);
        }
        if let Some(pitch) = number(PITCH_KEY) {
            effects.pitch = pitch.clamp(PITCH_RANGE.0, PITCH_RANGE.1);
        }
        let saved: Vec<f32> = settings
            .get_raw(EQ_KEY)
            .ok()
            .flatten()
            .unwrap_or_default()
            .split(',')
            .filter_map(|value| value.trim().parse().ok())
            .collect();
        let gain = |db: f32| db.clamp(GAIN_RANGE.0, GAIN_RANGE.1);
        if saved.len() >= 12 {
            effects.tone.bass_db = gain(saved[0]);
            effects.tone.virtualizer = saved[1].clamp(VIRTUALIZER_RANGE.0, VIRTUALIZER_RANGE.1);
            for (band, db) in effects.tone.eq_db.iter_mut().zip(&saved[2..12]) {
                *band = gain(*db);
            }
            if let Some(preamp) = saved.get(12) {
                effects.tone.preamp_db = gain(*preamp);
            }
        }
        effects
    }

    /// The settings rows this state saves as.
    pub fn rows(&self) -> [(&'static str, String); 3] {
        let tone = &self.tone;
        let float = |value: f32| ym_settings::kotlin_float(value);
        let bands = tone.eq_db.iter().copied().map(float).collect::<Vec<_>>().join(",");
        [
            (SPEED_KEY, float(self.speed)),
            (PITCH_KEY, float(self.pitch)),
            (EQ_KEY, format!("{},{},{bands},{}", float(tone.bass_db), float(tone.virtualizer), float(tone.preamp_db))),
        ]
    }
}

/// The preset whose curve `bands` are (within a fifth of a dB of each band), if any.
pub fn preset_of(bands: &[f32; 10]) -> Option<usize> {
    EQ_PRESETS.iter().position(|(_, curve)| curve.iter().zip(bands).all(|(preset, band)| (preset - band).abs() < 0.2))
}

/// The name of `bands`' preset, or Custom.
pub fn preset_name(bands: &[f32; 10]) -> &'static str {
    preset_of(bands).map(|index| t(EQ_PRESETS[index].0)).unwrap_or(t(Str::SoundPresetCustom))
}

/// The equalizer's named curves.
pub const EQ_PRESETS: [(Str, [f32; 10]); 9] = [
    (Str::SoundPresetFlat, [0.0; 10]),
    (Str::SoundPresetBass, [6.0, 5.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]),
    (Str::SoundPresetTreble, [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 3.0, 5.0, 6.0]),
    (Str::SoundPresetVoice, [-4.0, -3.0, -1.0, 1.0, 3.0, 4.0, 3.0, 1.0, 0.0, -1.0]),
    (Str::SoundPresetJazz, [4.0, 3.0, 1.0, 2.0, -2.0, -2.0, 0.0, 1.0, 3.0, 4.0]),
    (Str::SoundPresetRock, [5.0, 4.0, 3.0, 1.0, -1.0, -1.0, 1.0, 3.0, 4.0, 5.0]),
    (Str::SoundPresetPop, [-1.0, 2.0, 4.0, 4.0, 1.0, -1.0, -2.0, -2.0, 1.0, 2.0]),
    (Str::SoundPresetClassical, [5.0, 4.0, 3.0, 2.0, -1.0, -1.0, 0.0, 2.0, 3.0, 4.0]),
    (Str::SoundPresetElectronic, [4.0, 3.0, 1.0, 0.0, -2.0, 2.0, 1.0, 2.0, 3.0, 4.0]),
];
