// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

//! Encodes 48 kHz float PCM to MP3 with LAME: constant 128 kbps, the input's channel count, joint
//! stereo for stereo.
//!
//! The stream starts with LAME's "Info" frame, as every LAME-encoded CBR file does. It is a valid,
//! silent MP3 frame that carries the frame count and the encoder's delay and padding, so a decoder
//! that reads it reports the exact duration and can play without the padding; one that does not
//! plays 24 ms of silence first. LAME can only fill it in at the end, so the caller writes
//! [`Mp3Encoder::info_frame`] over the first bytes of the file once everything else is written.

use std::ffi::c_int;

use mp3lame_sys as lame;

use crate::oggopus::SAMPLE_RATE;
use crate::{Error, Result};

pub const BITRATE_KBPS: u32 = 128;

/// LAME's own default (`-q 3`): noise shaping and joint stereo decisions on, no slow outer loop.
const QUALITY: c_int = 3;

pub struct Mp3Encoder {
    lame: lame::lame_t,
    channels: usize,
    out: Vec<u8>,
}

impl Mp3Encoder {
    pub fn new(channels: usize) -> Result<Self> {
        if channels != 1 && channels != 2 {
            return Err(Error::failed(format!("cannot encode {channels} channels to MP3")));
        }
        // SAFETY: plain FFI call; the result is checked before use.
        let handle = unsafe { lame::lame_init() };
        if handle.is_null() {
            return Err(Error::failed("could not create the MP3 encoder"));
        }
        // From here `encoder` owns the handle and closes it on every path.
        let encoder = Mp3Encoder { lame: handle, channels, out: Vec::new() };
        let mode = if channels == 2 { lame::MPEG_mode::JOINT_STEREO } else { lame::MPEG_mode::MONO };
        // SAFETY: `handle` is a live encoder that has not been initialised yet, which is when the
        // setters may be called. No report functions: LAME then prints nothing itself, and its
        // failures reach the caller as return codes.
        let status = unsafe {
            lame::lame_set_errorf(handle, None);
            lame::lame_set_debugf(handle, None);
            lame::lame_set_msgf(handle, None);
            let mut failed = 0;
            failed |= lame::lame_set_num_channels(handle, channels as c_int);
            failed |= lame::lame_set_in_samplerate(handle, SAMPLE_RATE as c_int);
            failed |= lame::lame_set_out_samplerate(handle, SAMPLE_RATE as c_int);
            failed |= lame::lame_set_mode(handle, mode);
            failed |= lame::lame_set_VBR(handle, lame::vbr_mode::vbr_off);
            failed |= lame::lame_set_brate(handle, BITRATE_KBPS as c_int);
            failed |= lame::lame_set_quality(handle, QUALITY);
            failed |= lame::lame_set_bWriteVbrTag(handle, 1);
            if failed != 0 {
                -1
            } else {
                lame::lame_init_params(handle)
            }
        };
        if status < 0 {
            return Err(Error::failed(format!("could not set up the MP3 encoder (LAME error {status})")));
        }
        Ok(encoder)
    }

    /// Encodes interleaved samples (full scale is ±1.0) and returns the MP3 bytes that are ready,
    /// which may be none: LAME keeps what does not yet fill a frame.
    pub fn encode(&mut self, interleaved: &[f32]) -> Result<&[u8]> {
        let frames = interleaved.len() / self.channels;
        // LAME's documented worst case for one call.
        self.out.resize(frames * 5 / 4 + 7200, 0);
        let samples = interleaved.as_ptr();
        // SAFETY: `interleaved` holds `frames` frames of `self.channels` samples, and `out` has
        // the size passed. The mono call reads only the left pointer.
        let written = unsafe {
            if self.channels == 2 {
                lame::lame_encode_buffer_interleaved_ieee_float(self.lame, samples, frames as c_int, self.out.as_mut_ptr(), self.out.len() as c_int)
            } else {
                lame::lame_encode_buffer_ieee_float(self.lame, samples, samples, frames as c_int, self.out.as_mut_ptr(), self.out.len() as c_int)
            }
        };
        self.written(written)
    }

    /// Encodes what LAME still holds, padded to whole frames. Call once, after the last `encode`.
    pub fn flush(&mut self) -> Result<&[u8]> {
        self.out.resize(7200, 0);
        // SAFETY: `out` has the size passed, LAME's documented minimum for a flush.
        let written = unsafe { lame::lame_encode_flush(self.lame, self.out.as_mut_ptr(), self.out.len() as c_int) };
        self.written(written)
    }

    /// The finished Info frame, to be written at the start of the file in place of the empty one
    /// that `encode` produced first. Call after `flush`.
    pub fn info_frame(&mut self) -> Result<&[u8]> {
        // One frame; LAME's largest is 2880 bytes.
        self.out.resize(2880, 0);
        // SAFETY: `out` has the size passed.
        let written = unsafe { lame::lame_get_lametag_frame(self.lame, self.out.as_mut_ptr(), self.out.len()) };
        if written == 0 || written > self.out.len() {
            return Err(Error::failed("the MP3 encoder produced no Info frame"));
        }
        Ok(&self.out[..written])
    }

    fn written(&self, written: c_int) -> Result<&[u8]> {
        if written < 0 {
            return Err(Error::failed(format!("the MP3 encoder failed (LAME error {written})")));
        }
        Ok(&self.out[..written as usize])
    }
}

impl Drop for Mp3Encoder {
    fn drop(&mut self) {
        // SAFETY: the handle came from lame_init and is closed once.
        unsafe { lame::lame_close(self.lame) };
    }
}
