// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

//! What the tests need and the program does not: an Ogg Opus writer (libopus's encoder and the
//! ogg crate's page writer), so no audio file is checked in, and a reader of MP3 frame headers.
//! `examples/make-ogg-opus.rs` uses the writer too.

#![allow(dead_code)]

use std::collections::BTreeSet;
use std::ffi::c_int;
use std::io::{self, Write};

use ogg::writing::{PacketWriteEndInfo, PacketWriter};
use opusic_sys as opus;

pub const RATE: u64 = 48_000;
/// Every packet written here is 20 ms.
pub const PACKET_FRAMES: u64 = 960;

/// An Ogg Opus file to write. `frames` counts samples per channel at 48 kHz.
#[derive(Clone)]
pub struct Source {
    pub channels: usize,
    pub frames: u64,
    /// The pre-skip to declare. `None` is the encoder's own delay, the least there can be; a larger
    /// one is honoured by encoding that much more silence first, as encoders that declare a large
    /// pre-skip do.
    pub pre_skip: Option<u16>,
    pub output_gain: i16,
    /// Written to the header as it is; the audio is always encoded as family 0.
    pub mapping_family: u8,
    pub packets_per_page: u64,
    /// Bytes of padding in the comment header. More than a page holds (65 025) spreads it over pages.
    pub tags_padding: usize,
    /// Mark the last page as the stream's last. Without the mark a reader cannot trim the end.
    pub mark_last_page: bool,
    /// Add this many samples to the position of every page from the given page of audio on, the
    /// way positions jump ahead of the audio in some real files.
    pub position_jump: Option<(u64, u64)>,
}

impl Source {
    pub fn new(channels: usize, frames: u64) -> Self {
        Source {
            channels,
            frames,
            pre_skip: None,
            output_gain: 0,
            mapping_family: 0,
            packets_per_page: 50,
            tags_padding: 0,
            mark_last_page: true,
            position_jump: None,
        }
    }

    pub fn seconds(channels: usize, seconds: f64) -> Self {
        Self::new(channels, (seconds * RATE as f64) as u64)
    }
}

struct Encoder(*mut opus::OpusEncoder);

impl Drop for Encoder {
    fn drop(&mut self) {
        // SAFETY: the pointer came from opus_encoder_create and is destroyed once.
        unsafe { opus::opus_encoder_destroy(self.0) }
    }
}

pub const SERIAL: u32 = 0x5350_4f54;

/// Writes `source` to `out`. `signal(frame, channel)` gives each sample; full scale is ±1.0.
pub fn write_ogg_opus(out: &mut impl Write, source: &Source, signal: &mut dyn FnMut(u64, usize) -> f32) -> io::Result<()> {
    let channels = source.channels;
    let mut status: c_int = 0;
    // SAFETY: plain FFI calls; the encoder is checked before use, and OPUS_GET_LOOKAHEAD writes
    // one opus_int32 through the pointer.
    let encoder = Encoder(unsafe { opus::opus_encoder_create(RATE as i32, channels as c_int, opus::OPUS_APPLICATION_AUDIO, &mut status) });
    assert!(!encoder.0.is_null() && status == opus::OPUS_OK, "opus_encoder_create: {status}");
    let mut lookahead: i32 = 0;
    let status = unsafe { opus::opus_encoder_ctl(encoder.0, opus::OPUS_GET_LOOKAHEAD_REQUEST, &mut lookahead as *mut i32) };
    assert_eq!(status, opus::OPUS_OK);
    let lookahead = lookahead as u64;
    let pre_skip = source.pre_skip.map_or(lookahead, u64::from);
    assert!(pre_skip >= lookahead && pre_skip <= u64::from(u16::MAX), "pre-skip {pre_skip} with an encoder delay of {lookahead}");
    // The encoder's output lags its input by `lookahead`. Feeding `lead` samples of silence first
    // puts frame 0 of the signal exactly `pre_skip` samples into the decoded audio.
    let lead = pre_skip - lookahead;
    // The decoded audio must reach pre_skip + frames; the last page's position says to stop there.
    let end = pre_skip + source.frames;
    let packet_count = end.div_ceil(PACKET_FRAMES).max(1);

    let mut writer = PacketWriter::new(out);
    let mut head = b"OpusHead".to_vec();
    head.push(1);
    head.push(channels as u8);
    head.extend_from_slice(&(pre_skip as u16).to_le_bytes());
    head.extend_from_slice(&(RATE as u32).to_le_bytes());
    head.extend_from_slice(&source.output_gain.to_le_bytes());
    head.push(source.mapping_family);
    writer.write_packet(head, SERIAL, PacketWriteEndInfo::EndPage, 0)?;

    let vendor = b"studio-pocket-transcode tests";
    let mut tags = b"OpusTags".to_vec();
    tags.extend_from_slice(&(vendor.len() as u32).to_le_bytes());
    tags.extend_from_slice(vendor);
    tags.extend_from_slice(&1u32.to_le_bytes());
    let comment_length = "PADDING=".len() + source.tags_padding;
    tags.extend_from_slice(&(comment_length as u32).to_le_bytes());
    tags.extend_from_slice(b"PADDING=");
    tags.resize(tags.len() + source.tags_padding, b'.');
    writer.write_packet(tags, SERIAL, PacketWriteEndInfo::EndPage, 0)?;

    let mut pcm = vec![0f32; PACKET_FRAMES as usize * channels];
    let mut packet = vec![0u8; 4000];
    for index in 0..packet_count {
        for (offset, frame) in pcm.chunks_exact_mut(channels).enumerate() {
            let position = index * PACKET_FRAMES + offset as u64;
            for (channel, sample) in frame.iter_mut().enumerate() {
                *sample = if position >= lead && position - lead < source.frames { signal(position - lead, channel) } else { 0.0 };
            }
        }
        // SAFETY: `pcm` holds one packet of `channels` channels and `packet` has the size passed.
        let length = unsafe { opus::opus_encode_float(encoder.0, pcm.as_ptr(), PACKET_FRAMES as c_int, packet.as_mut_ptr(), packet.len() as i32) };
        assert!(length > 0, "opus_encode_float: {length}");

        let last = index + 1 == packet_count;
        let page = index / source.packets_per_page;
        let jump = source.position_jump.map_or(0, |(from_page, samples)| if page >= from_page { samples } else { 0 });
        let position = if last { end } else { (index + 1) * PACKET_FRAMES } + jump;
        let ends = if last && source.mark_last_page {
            PacketWriteEndInfo::EndStream
        } else if last || (index + 1) % source.packets_per_page == 0 {
            PacketWriteEndInfo::EndPage
        } else {
            PacketWriteEndInfo::NormalPacket
        };
        writer.write_packet(packet[..length as usize].to_vec(), SERIAL, ends, position)?;
    }
    Ok(())
}

pub fn ogg_opus(source: &Source, signal: &mut dyn FnMut(u64, usize) -> f32) -> Vec<u8> {
    let mut bytes = Vec::new();
    write_ogg_opus(&mut bytes, source, signal).expect("writing to memory");
    bytes
}

/// A sine sweep from 200 Hz to 6 kHz over `frames`, a different phase in each channel.
pub fn sweep(frames: u64) -> impl FnMut(u64, usize) -> f32 {
    let length = frames.max(1) as f64 / RATE as f64;
    move |frame, channel| {
        let t = frame as f64 / RATE as f64;
        let phase = std::f64::consts::TAU * (200.0 * t + (6000.0 - 200.0) * t * t / (2.0 * length));
        (0.4 * (phase + channel as f64 * 1.3).sin()) as f32
    }
}

/// What an MP3 file's frame headers say, and its Info frame if it starts with one.
#[derive(Debug)]
pub struct Mp3 {
    /// Every frame in the file, the Info frame included.
    pub frame_count: u64,
    pub versions: BTreeSet<&'static str>,
    pub bitrates_kbps: BTreeSet<u32>,
    pub sample_rates: BTreeSet<u32>,
    /// The two-bit channel mode of each frame: 0 stereo, 1 joint stereo, 2 dual channel, 3 mono.
    pub channel_modes: BTreeSet<u8>,
    pub info: Option<InfoFrame>,
}

#[derive(Debug)]
pub struct InfoFrame {
    /// "Info" for constant bitrate, "Xing" for variable.
    pub tag: String,
    /// Frames of audio: all but the Info frame.
    pub frames: u64,
    /// The whole file.
    pub bytes: u64,
    pub encoder: String,
    pub delay: u64,
    pub padding: u64,
}

impl Mp3 {
    /// The audio a decoder that reads the Info frame plays, in samples per channel.
    pub fn samples(&self) -> u64 {
        let info = self.info.as_ref().expect("an Info frame");
        info.frames * 1152 - info.delay - info.padding
    }

    /// The length a decoder that knows nothing of the Info frame reports, in samples per channel.
    pub fn plain_samples(&self) -> u64 {
        self.frame_count * 1152
    }
}

/// Walks the file frame by frame; panics on anything that is not an MPEG-1 Layer III frame
/// following directly on the one before.
pub fn parse_mp3(bytes: &[u8]) -> Mp3 {
    const BITRATES: [u32; 15] = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320];
    const RATES: [u32; 3] = [44_100, 48_000, 32_000];
    let mut mp3 = Mp3 {
        frame_count: 0,
        versions: BTreeSet::new(),
        bitrates_kbps: BTreeSet::new(),
        sample_rates: BTreeSet::new(),
        channel_modes: BTreeSet::new(),
        info: None,
    };
    let mut at = 0;
    while at < bytes.len() {
        let header = &bytes[at..(at + 4).min(bytes.len())];
        assert!(header.len() == 4 && header[0] == 0xff && header[1] & 0xe0 == 0xe0, "no frame sync at byte {at} of {}", bytes.len());
        assert_eq!(header[1] & 0x1e, 0x1a, "frame at byte {at} is not MPEG-1 Layer III");
        let protected = header[1] & 1 == 0;
        let bitrate = BITRATES[usize::from(header[2] >> 4)];
        let rate = RATES[usize::from(header[2] >> 2 & 3)];
        let padding = u32::from(header[2] >> 1 & 1);
        let mode = header[3] >> 6;
        assert!(bitrate != 0, "free-format frame at byte {at}");
        let length = (144_000 * bitrate / rate + padding) as usize;
        assert!(at + length <= bytes.len(), "the frame at byte {at} is cut short");

        if mp3.frame_count == 0 {
            let side_info = if mode == 3 { 17 } else { 32 };
            let tag_at = at + 4 + if protected { 2 } else { 0 } + side_info;
            let tag = &bytes[tag_at..at + length];
            if tag.starts_with(b"Info") || tag.starts_with(b"Xing") {
                mp3.info = Some(parse_info(tag));
            }
        }
        mp3.versions.insert("MPEG-1 Layer III");
        mp3.bitrates_kbps.insert(bitrate);
        mp3.sample_rates.insert(rate);
        mp3.channel_modes.insert(mode);
        mp3.frame_count += 1;
        at += length;
    }
    mp3
}

fn parse_info(tag: &[u8]) -> InfoFrame {
    let u32_at = |at: usize| u64::from(u32::from_be_bytes(tag[at..at + 4].try_into().unwrap()));
    let flags = u32_at(4);
    assert_eq!(flags & 0xf, 0xf, "LAME writes the frame count, byte count, seek table and quality");
    // tag(4) flags(4) frames(4) bytes(4) seek table(100) quality(4), then LAME's own part:
    // version(9) method(1) lowpass(1) replay gain(8) flags(1) bitrate(1) delay and padding(3).
    let lame = &tag[120..];
    let gapless = &lame[21..24];
    InfoFrame {
        tag: String::from_utf8_lossy(&tag[..4]).into_owned(),
        frames: u32_at(8),
        bytes: u32_at(12),
        encoder: String::from_utf8_lossy(&lame[..9]).trim_end().to_owned(),
        delay: u64::from(gapless[0]) << 4 | u64::from(gapless[1]) >> 4,
        padding: (u64::from(gapless[1]) & 0xf) << 8 | u64::from(gapless[2]),
    }
}
