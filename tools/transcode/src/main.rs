// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

//! studio-pocket-transcode: converts one Ogg Opus file to a 128 kbps, 48 kHz MP3.
//!
//! ```text
//! studio-pocket-transcode --version          prints "studio-pocket-transcode <version>"
//! studio-pocket-transcode <input> <output>   converts one file
//! ```
//!
//! While converting, standard output carries one line at a time, each flushed as it is written:
//! `progress <input bytes read> <input bytes total>` once at the start and then at most about four
//! times a second, and `done <output bytes>` at the end. Nothing else is written there. Errors are
//! one line on standard error.
//!
//! Exit codes: 0 done; 2 wrong arguments; 3 the input is not Ogg Opus that this program reads (not
//! Ogg, not Opus, or more than two channels), and the output path was not touched; 4 anything else
//! (I/O, a corrupt or truncated stream, an encoder error), with the partial output removed.
//!
//! It reads no configuration and no environment variables, and opens nothing but the two files.
//! To stop it, kill it; the caller then deletes the partial output.

use std::ffi::OsString;
use std::io::{self, Write};
use std::path::Path;
use std::process::ExitCode;
use std::time::{Duration, Instant};
use std::{env, fs, panic};

use studio_pocket_transcode::convert::{convert, Throttle};

const NAME: &str = "studio-pocket-transcode";
const VERSION: &str = env!("CARGO_PKG_VERSION");

const EXIT_USAGE: u8 = 2;
const EXIT_FAILED: u8 = 4;

/// The least time between two `progress` lines.
const PROGRESS_INTERVAL: Duration = Duration::from_millis(250);

fn main() -> ExitCode {
    // A bug must not look like anything else: one line, exit code 4, no partial output (the
    // output file removes itself as the panic unwinds).
    panic::set_hook(Box::new(|info| complain(&format!("internal error: {info}"))));

    let arguments: Vec<OsString> = env::args_os().skip(1).collect();
    match arguments.as_slice() {
        [flag] if flag == "--version" => match line(&format!("{NAME} {VERSION}")) {
            Ok(()) => ExitCode::SUCCESS,
            Err(_) => ExitCode::from(EXIT_FAILED),
        },
        [input, output] => run(Path::new(input), Path::new(output)),
        _ => {
            complain(&format!("usage: {NAME} <input.opus> <output.mp3>, or {NAME} --version"));
            ExitCode::from(EXIT_USAGE)
        }
    }
}

fn run(input: &Path, output: &Path) -> ExitCode {
    if same_file(input, output) {
        complain("the input and the output are the same file");
        return ExitCode::from(EXIT_USAGE);
    }
    let converted = panic::catch_unwind(|| {
        let mut throttle = Throttle::new(PROGRESS_INTERVAL);
        convert(input, output, &mut |consumed, total| {
            if throttle.ready(Instant::now()) {
                line(&format!("progress {consumed} {total}"))?;
            }
            Ok(())
        })
    });
    match converted {
        Ok(Ok(bytes)) => match line(&format!("done {bytes}")) {
            Ok(()) => ExitCode::SUCCESS,
            // Nobody is reading: this is not a success the caller will hear of, so leave nothing.
            Err(error) => {
                let _ = fs::remove_file(output);
                complain(&format!("could not report the result: {error}"));
                ExitCode::from(EXIT_FAILED)
            }
        },
        Ok(Err(error)) => {
            complain(&error.message);
            ExitCode::from(error.exit_code())
        }
        // The panic hook has already said why.
        Err(_) => ExitCode::from(EXIT_FAILED),
    }
}

/// Writes one line to standard output and flushes it.
fn line(text: &str) -> io::Result<()> {
    let mut out = io::stdout().lock();
    writeln!(out, "{text}")?;
    out.flush()
}

/// Writes one line to standard error, whatever the message holds.
fn complain(message: &str) {
    let message: String = message.chars().map(|c| if c.is_control() { ' ' } else { c }).collect();
    let _ = writeln!(io::stderr(), "{NAME}: {message}");
}

/// Whether the two paths name one existing file. Converting a file onto itself would destroy it.
fn same_file(a: &Path, b: &Path) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        if let (Ok(a), Ok(b)) = (fs::metadata(a), fs::metadata(b)) {
            if a.dev() == b.dev() && a.ino() == b.ino() {
                return true;
            }
        }
    }
    matches!((fs::canonicalize(a), fs::canonicalize(b)), (Ok(a), Ok(b)) if a == b)
}
