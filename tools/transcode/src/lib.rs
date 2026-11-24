// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

//! Ogg Opus to MP3, one file at a time, for the Studio Pocket player (Android 4.4 has no Opus
//! decoder). The program in `main.rs` is the interface; this library holds its parts so that the
//! tests can reach them: [`oggopus`] reads and decodes, [`mp3`] encodes, [`convert`] joins the
//! two and writes the file.

pub mod convert;
pub mod mp3;
pub mod oggopus;

use std::fmt;

/// What went wrong, as the caller needs to know it: each kind is one exit code.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorKind {
    /// The input is not something this program reads: not Ogg, not Opus, or an Opus channel
    /// mapping other than family 0 (mono or stereo). Exit code 3; the caller tries its other
    /// converter.
    Unsupported,
    /// Anything else: I/O, a corrupt or truncated stream, an encoder error. Exit code 4.
    Failed,
}

#[derive(Debug)]
pub struct Error {
    pub kind: ErrorKind,
    pub message: String,
}

impl Error {
    pub fn unsupported(message: impl Into<String>) -> Self {
        Error { kind: ErrorKind::Unsupported, message: message.into() }
    }

    pub fn failed(message: impl Into<String>) -> Self {
        Error { kind: ErrorKind::Failed, message: message.into() }
    }

    pub fn exit_code(&self) -> u8 {
        match self.kind {
            ErrorKind::Unsupported => 3,
            ErrorKind::Failed => 4,
        }
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for Error {}

pub type Result<T> = std::result::Result<T, Error>;
