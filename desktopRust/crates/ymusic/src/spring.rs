//! Compose's default `spring()` (damping ratio 1, stiffness 1500), the motion of `animate*AsState`:
//! critically damped, so it settles without overshoot, and a new target mid-way carries the velocity
//! on.

use std::time::Instant;

/// `sqrt(stiffness)` with Compose's `StiffnessMedium`, per second.
const OMEGA: f32 = 38.729_835;
/// Compose's `Dp.VisibilityThreshold`: this near its target the value is there.
const THRESHOLD: f32 = 0.1;

pub struct Spring {
    origin: f32,
    velocity: f32,
    target: f32,
    started: Instant,
}

impl Spring {
    pub fn at_rest(value: f32, now: Instant) -> Self {
        Self { origin: value, velocity: 0., target: value, started: now }
    }

    /// The value and velocity (per second) at `now`.
    fn state(&self, now: Instant) -> (f32, f32) {
        let elapsed = now.saturating_duration_since(self.started).as_secs_f32();
        let start = self.origin - self.target;
        let slope = self.velocity + OMEGA * start;
        let decay = (-OMEGA * elapsed).exp();
        let offset = (start + slope * elapsed) * decay;
        let velocity = (slope - OMEGA * (start + slope * elapsed)) * decay;
        (self.target + offset, velocity)
    }

    pub fn settled(&self, now: Instant) -> bool {
        let (value, velocity) = self.state(now);
        (value - self.target).abs() < THRESHOLD && velocity.abs() < OMEGA * THRESHOLD
    }

    pub fn value(&self, now: Instant) -> f32 {
        if self.settled(now) { self.target } else { self.state(now).0 }
    }

    /// Heads for `target` from wherever the value is at `now`, at the speed it has there.
    pub fn retarget(&mut self, target: f32, now: Instant) {
        if target == self.target {
            return;
        }
        let (value, velocity) = if self.settled(now) { (self.target, 0.) } else { self.state(now) };
        *self = Self { origin: value, velocity, target, started: now };
    }
}
