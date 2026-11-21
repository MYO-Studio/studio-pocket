// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

//! One conversion: read, decode and encode a packet at a time, writing as it goes. Memory use does
//! not depend on the length of the audio: what is held is one Ogg page, one decoded packet (at
//! most 120 ms), LAME's own state and two 64 KiB file buffers.

use std::fs::{self, File};
use std::io::{self, BufReader, BufWriter, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use crate::mp3::Mp3Encoder;
use crate::oggopus::OggOpusDecoder;
use crate::{Error, Result};

const FILE_BUFFER: usize = 64 * 1024;

/// Converts the Ogg Opus file `input` to an MP3 at `output` and returns the MP3's size in bytes.
///
/// `progress` is called with the input bytes read so far and the input's size: once when the
/// input has been recognised, then after every packet. An error from it stops the conversion.
///
/// `output` is created (or emptied) only once the input is known to be Ogg Opus that this program
/// reads; an input that is not leaves `output` untouched. After that point any failure removes the
/// file, so a file left at `output` is always a complete MP3.
pub fn convert(input: &Path, output: &Path, progress: &mut dyn FnMut(u64, u64) -> io::Result<()>) -> Result<u64> {
    let file = File::open(input).map_err(|error| Error::failed(format!("could not open {}: {error}", input.display())))?;
    let total = file.metadata().map_err(|error| Error::failed(format!("could not read the size of {}: {error}", input.display())))?.len();
    let mut decoder = OggOpusDecoder::open(BufReader::with_capacity(FILE_BUFFER, file))?;
    let mut encoder = Mp3Encoder::new(decoder.channels())?;
    let mut out = OutputFile::create(output)?;

    let mut report =
        |consumed: u64| progress(consumed.min(total), total).map_err(|error| Error::failed(format!("could not report progress: {error}")));
    report(decoder.bytes_consumed())?;
    while let Some(samples) = decoder.next_chunk()? {
        out.write(encoder.encode(samples)?)?;
        report(decoder.bytes_consumed())?;
    }
    out.write(encoder.flush()?)?;
    out.replace_start(encoder.info_frame()?)?;
    out.finish()
}

/// The output file while it is being written. Dropped before `finish`, it deletes the file.
struct OutputFile {
    path: PathBuf,
    file: Option<BufWriter<File>>,
    written: u64,
    finished: bool,
}

impl OutputFile {
    fn create(path: &Path) -> Result<Self> {
        let file = File::create(path).map_err(|error| Error::failed(format!("could not create {}: {error}", path.display())))?;
        Ok(OutputFile { path: path.to_owned(), file: Some(BufWriter::with_capacity(FILE_BUFFER, file)), written: 0, finished: false })
    }

    fn write_error(&self, error: io::Error) -> Error {
        Error::failed(format!("could not write {}: {error}", self.path.display()))
    }

    fn write(&mut self, bytes: &[u8]) -> Result<()> {
        let file = self.file.as_mut().expect("the file is open until finish");
        file.write_all(bytes).map_err(|error| self.write_error(error))?;
        self.written += bytes.len() as u64;
        Ok(())
    }

    /// Writes `bytes` over the first bytes of the file; the file does not grow.
    fn replace_start(&mut self, bytes: &[u8]) -> Result<()> {
        if bytes.len() as u64 > self.written {
            return Err(Error::failed("the MP3 is shorter than its own first frame"));
        }
        let file = self.file.as_mut().expect("the file is open until finish");
        file.seek(SeekFrom::Start(0)).and_then(|_| file.write_all(bytes)).map_err(|error| self.write_error(error))
    }

    /// Flushes and closes the file, which now stays. Returns its size.
    fn finish(mut self) -> Result<u64> {
        let file = self.file.take().expect("the file is open until finish");
        file.into_inner().map_err(|error| self.write_error(error.into_error()))?;
        self.finished = true;
        Ok(self.written)
    }
}

impl Drop for OutputFile {
    fn drop(&mut self) {
        // Close first: Windows does not delete an open file.
        self.file.take();
        if !self.finished {
            let _ = fs::remove_file(&self.path);
        }
    }
}

/// Lets something through at most once per `interval`; the first time always.
pub struct Throttle {
    interval: Duration,
    last: Option<Instant>,
}

impl Throttle {
    pub fn new(interval: Duration) -> Self {
        Throttle { interval, last: None }
    }

    pub fn ready(&mut self, now: Instant) -> bool {
        if self.last.is_some_and(|last| now.saturating_duration_since(last) < self.interval) {
            return false;
        }
        self.last = Some(now);
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn throttle_lets_the_first_through_and_then_one_per_interval() {
        let start = Instant::now();
        let at = |ms: u64| start + Duration::from_millis(ms);
        let mut throttle = Throttle::new(Duration::from_millis(250));
        let passed: Vec<u64> = [0, 1, 100, 249, 250, 251, 499, 500, 2000, 2100, 2250].into_iter().filter(|&ms| throttle.ready(at(ms))).collect();
        assert_eq!(passed, [0, 250, 500, 2000, 2250]);
    }
}
