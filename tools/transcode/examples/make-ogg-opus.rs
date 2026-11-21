// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

//! Writes an Ogg Opus file of any length for trying the converter on, with the same writer the
//! tests use: `cargo run --release --example make-ogg-opus -- <output> <seconds> [channels]`.
//! scripts/build-transcode.sh makes its smoke-test input with it.
//!
//! The audio is synthetic but busy, so that neither codec has an easy time: gliding tones under a
//! syllable-like envelope, over noise, different in each channel.

#[path = "../tests/support/mod.rs"]
mod support;

use std::f64::consts::TAU;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::process::ExitCode;

use support::{write_ogg_opus, Source, RATE};

fn main() -> ExitCode {
    let arguments: Vec<String> = std::env::args().skip(1).collect();
    let parsed = match arguments.as_slice() {
        [output, seconds] => seconds.parse::<f64>().ok().map(|seconds| (output, seconds, 2)),
        [output, seconds, channels] => {
            seconds.parse::<f64>().ok().zip(channels.parse::<usize>().ok()).map(|(seconds, channels)| (output, seconds, channels))
        }
        _ => None,
    };
    let Some((output, seconds, channels)) = parsed.filter(|&(_, seconds, channels)| seconds > 0.0 && (channels == 1 || channels == 2)) else {
        eprintln!("usage: make-ogg-opus <output> <seconds> [channels: 1 or 2]");
        return ExitCode::from(2);
    };

    let source = Source::seconds(channels, seconds);
    let mut noise = 0x2545_f491_4f6c_dd1du64;
    let mut signal = move |frame: u64, channel: usize| {
        let t = frame as f64 / RATE as f64;
        let side = channel as f64;
        // A voice-like fundamental that wanders, with harmonics, switched on and off like syllables.
        let pitch = 140.0 + 40.0 * (TAU * 0.31 * t + side).sin();
        let phase = TAU * pitch * t;
        let voice = phase.sin() + 0.5 * (2.0 * phase).sin() + 0.3 * (3.0 * phase + side).sin() + 0.2 * (5.0 * phase).sin();
        let syllable = (TAU * 3.7 * t + 0.4 * side).sin().max(0.0).powf(0.6);
        // A slow sweep through the upper band, as music behind the voice would fill it.
        let sweep = (TAU * (1500.0 + 1200.0 * (TAU * 0.05 * t).sin()) * t + 2.0 * side).sin();
        noise ^= noise << 13;
        noise ^= noise >> 7;
        noise ^= noise << 17;
        let hiss = (noise >> 40) as f64 / (1u64 << 23) as f64 - 1.0;
        (0.22 * voice * syllable + 0.08 * sweep + 0.03 * hiss) as f32
    };

    let written = File::create(output).and_then(|file| {
        let mut file = BufWriter::new(file);
        write_ogg_opus(&mut file, &source, &mut signal)?;
        file.flush()
    });
    match written {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("make-ogg-opus: {output}: {error}");
            ExitCode::FAILURE
        }
    }
}
