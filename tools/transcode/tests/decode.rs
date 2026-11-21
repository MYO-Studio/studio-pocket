// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

//! The reading half on its own: what comes out of `OggOpusDecoder` for files written by
//! `support::write_ogg_opus`. The lengths are exact; where the audio sits is checked with a tone
//! that starts at a known sample.

mod support;

use std::io::Cursor;

use ogg::writing::{PacketWriteEndInfo, PacketWriter};
use studio_pocket_transcode::oggopus::OggOpusDecoder;
use studio_pocket_transcode::{Error, ErrorKind};
use support::{ogg_opus, Source, PACKET_FRAMES, RATE};

/// Decodes a whole file to one interleaved buffer.
fn decode(file: &[u8]) -> Result<(usize, Vec<f32>), Error> {
    let mut decoder = OggOpusDecoder::open(Cursor::new(file))?;
    let mut pcm = Vec::new();
    while let Some(samples) = decoder.next_chunk()? {
        pcm.extend_from_slice(samples);
    }
    assert_eq!(decoder.bytes_consumed(), file.len() as u64, "the whole file is read");
    Ok((decoder.channels(), pcm))
}

fn frames(file: &[u8]) -> u64 {
    let (channels, pcm) = decode(file).unwrap();
    (pcm.len() / channels) as u64
}

/// Silence, then a 1 kHz tone from `onset` to the end.
fn tone_from(onset: u64) -> impl FnMut(u64, usize) -> f32 {
    move |frame, _| if frame < onset { 0.0 } else { (0.5 * (std::f64::consts::TAU * 1000.0 * (frame - onset) as f64 / RATE as f64).sin()) as f32 }
}

/// The first frame whose left sample is clearly not silence.
fn onset(channels: usize, pcm: &[f32]) -> u64 {
    pcm.chunks_exact(channels).position(|frame| frame[0].abs() > 0.2).expect("a tone") as u64
}

fn rms(samples: impl Iterator<Item = f32>) -> f64 {
    let (sum, count) = samples.fold((0.0, 0u64), |(sum, count), sample| (sum + f64::from(sample).powi(2), count + 1));
    (sum / count as f64).sqrt()
}

#[test]
fn the_length_is_the_last_position_less_the_pre_skip() {
    // Lengths that are not whole packets: the last packet decodes to more, and the excess goes.
    for (channels, length) in [(1, 48_000), (2, 48_000), (1, 100_123), (2, 12_345), (1, 1), (2, 959), (1, 961), (2, 960 * 50), (1, 960 * 50 - 312)] {
        let file = ogg_opus(&Source::new(channels, length), &mut support::sweep(length));
        assert_eq!(frames(&file), length, "{channels} channel(s), {length} frames");
    }
}

#[test]
fn the_pre_skip_is_taken_from_the_start() {
    const ONSET: u64 = 9_000;
    const LENGTH: u64 = 30_000;
    let mut onsets = Vec::new();
    // The encoder's own delay (312), the 80 ms that opusenc declares, and more than one packet.
    for pre_skip in [None, Some(3_840), Some(5_000)] {
        let source = Source { pre_skip, ..Source::new(2, LENGTH) };
        let (channels, pcm) = decode(&ogg_opus(&source, &mut tone_from(ONSET))).unwrap();
        assert_eq!(pcm.len() as u64 / 2, LENGTH, "pre-skip {pre_skip:?}");
        let found = onset(channels, &pcm);
        // The tone reaches 0.2 within its first quarter cycle; the codec may smear it a little.
        assert!(found.abs_diff(ONSET) <= 48, "pre-skip {pre_skip:?}: the tone starts at {found}, not {ONSET}");
        onsets.push(found);
    }
    // Ignoring the pre-skip would move the tone by 312, 3840 and 5000 samples.
    assert!(onsets.iter().all(|&found| found.abs_diff(onsets[0]) <= 8), "{onsets:?}");
}

#[test]
fn the_end_is_trimmed_from_the_last_page_even_after_positions_jump() {
    // 2.5 pages of audio; from the second page on every position is 2312 samples ahead, as in a
    // file seen in the wild. The end must still be trimmed to the sample.
    let length = PACKET_FRAMES * 125 - 312 - 80;
    let source = Source { position_jump: Some((1, 2_312)), ..Source::new(2, length) };
    assert_eq!(frames(&ogg_opus(&source, &mut support::sweep(length))), length);
}

#[test]
fn an_unmarked_last_page_is_read_to_its_end() {
    let length = 20_000;
    let source = Source { mark_last_page: false, ..Source::new(1, length) };
    // Nothing says where the audio ends, so every packet is kept whole: 22 packets less the pre-skip.
    assert_eq!(frames(&ogg_opus(&source, &mut support::sweep(length))), 22 * PACKET_FRAMES - 312);
}

#[test]
fn the_output_gain_is_applied() {
    let length = 48_000;
    let level = |output_gain: i16| {
        let source = Source { output_gain, ..Source::new(1, length) };
        // A quiet tone: doubled, it stays well inside full scale, where the limiter does nothing.
        let (_, pcm) = decode(&ogg_opus(&source, &mut |frame, _| 0.5 * tone_from(0)(frame, 0))).unwrap();
        rms(pcm[4_800..].iter().copied())
    };
    let unity = level(0);
    // The gain is in 1/256 dB: -6.02 dB halves the amplitude, +6.02 dB doubles it.
    assert!((level(-1541) / unity - 0.5).abs() < 0.01, "{} against {unity}", level(-1541));
    assert!((level(1541) / unity - 2.0).abs() < 0.02, "{} against {unity}", level(1541));
}

#[test]
fn stereo_channels_stay_in_order() {
    let length = 24_000;
    let mut left_only = |frame: u64, channel: usize| if channel == 0 { tone_from(0)(frame, 0) } else { 0.0 };
    let (channels, pcm) = decode(&ogg_opus(&Source::new(2, length), &mut left_only)).unwrap();
    assert_eq!(channels, 2);
    let left = rms(pcm.chunks_exact(2).map(|frame| frame[0]));
    let right = rms(pcm.chunks_exact(2).map(|frame| frame[1]));
    assert!(left > 0.3 && right < 0.02, "left {left}, right {right}");
}

#[test]
fn a_comment_header_spread_over_pages_is_skipped() {
    let length = 10_000;
    let source = Source { tags_padding: 200_000, ..Source::new(2, length) };
    assert_eq!(frames(&ogg_opus(&source, &mut support::sweep(length))), length);
}

#[test]
fn what_follows_the_opus_stream_is_ignored() {
    let length = 10_000;
    let mut file = ogg_opus(&Source::new(1, length), &mut support::sweep(length));
    let stream_end = file.len() as u64;
    // A second stream chained on, as in a recorded radio stream, and then some junk.
    file.extend(ogg_opus(&Source::new(2, 5_000), &mut support::sweep(5_000)));
    file.extend_from_slice(b"TAG and whatever else was appended");
    let mut decoder = OggOpusDecoder::open(Cursor::new(&file)).unwrap();
    let mut decoded = 0;
    while let Some(samples) = decoder.next_chunk().unwrap() {
        decoded += samples.len() as u64;
    }
    assert_eq!(decoded, length);
    assert_eq!(decoder.bytes_consumed(), stream_end);
}

#[test]
fn another_stream_in_the_same_file_is_skipped() {
    let length = 30_000;
    let opus = ogg_opus(&Source { packets_per_page: 5, ..Source::new(1, length) }, &mut support::sweep(length));
    // Interleave the Opus stream's pages with those of a stream of something else. Its first page
    // goes before the Opus stream's, as the first pages of all streams come before anything else.
    let mut other = Vec::new();
    {
        let mut writer = PacketWriter::new(&mut other);
        writer.write_packet(b"fishead\0 not a codec this program knows".to_vec(), 7, PacketWriteEndInfo::EndPage, 0).unwrap();
        for index in 0..6u64 {
            let ends = if index == 5 { PacketWriteEndInfo::EndStream } else { PacketWriteEndInfo::EndPage };
            writer.write_packet(vec![index as u8; 300], 7, ends, index).unwrap();
        }
    }
    let (opus_pages, other_pages) = (pages(&opus), pages(&other));
    let mut file = other_pages[0].to_vec();
    for (index, page) in opus_pages.iter().enumerate() {
        file.extend_from_slice(page);
        if let Some(other_page) = other_pages.get(index + 1) {
            file.extend_from_slice(other_page);
        }
    }
    assert_eq!(frames(&file), length);
}

/// Splits an Ogg file into its pages.
fn pages(file: &[u8]) -> Vec<&[u8]> {
    let mut pages = Vec::new();
    let mut at = 0;
    while at < file.len() {
        let segments = usize::from(file[at + 26]);
        let body: usize = file[at + 27..at + 27 + segments].iter().map(|&length| usize::from(length)).sum();
        pages.push(&file[at..at + 27 + segments + body]);
        at += 27 + segments + body;
    }
    pages
}

fn kind(file: &[u8]) -> ErrorKind {
    decode(file).expect_err("an error").kind
}

#[test]
fn inputs_that_are_not_ogg_opus_are_unsupported() {
    let length = 5_000;
    assert_eq!(kind(b""), ErrorKind::Unsupported);
    assert_eq!(kind(b"Og"), ErrorKind::Unsupported);
    assert_eq!(kind(b"ID3\x04\0\0\0\0\0\x0a and then an MP3, say"), ErrorKind::Unsupported);
    assert_eq!(kind(&[0xff, 0xfb, 0x94, 0x00].repeat(200)), ErrorKind::Unsupported);

    // Ogg, but Vorbis: the first packet of the first stream is not an Opus header.
    let mut vorbis = Vec::new();
    {
        let mut writer = PacketWriter::new(&mut vorbis);
        writer.write_packet(b"\x01vorbis\0\0\0\0\x02\x44\xac\0\0".to_vec(), 1, PacketWriteEndInfo::EndPage, 0).unwrap();
        writer.write_packet(b"\x03vorbis".to_vec(), 1, PacketWriteEndInfo::EndStream, 0).unwrap();
    }
    assert_eq!(kind(&vorbis), ErrorKind::Unsupported);

    // Opus, but a channel mapping for surround sound.
    let surround = Source { mapping_family: 1, ..Source::new(2, length) };
    assert_eq!(kind(&ogg_opus(&surround, &mut support::sweep(length))), ErrorKind::Unsupported);
}

#[test]
fn broken_streams_fail() {
    let length = 100_000;
    let file = ogg_opus(&Source::new(2, length), &mut support::sweep(length));
    let boundaries: Vec<usize> = pages(&file)
        .iter()
        .scan(0, |end, page| {
            *end += page.len();
            Some(*end)
        })
        .collect();

    // Cut in the middle of a page: in its body, in its segment table, in its header.
    let third_page = boundaries[1];
    for cut in [file.len() - 100, third_page + 40, third_page + 10, third_page + 2] {
        let error = decode(&file[..cut]).expect_err("an error");
        assert_eq!(error.kind, ErrorKind::Failed, "cut at {cut}");
        assert!(error.message.contains("ends in the middle"), "cut at {cut}: {}", error.message);
    }
    // Cut inside the very first page: Ogg, but not readable far enough to say what.
    assert_eq!(kind(&file[..30]), ErrorKind::Failed);

    // A flipped bit in the audio fails the page's checksum.
    let mut flipped = file.clone();
    flipped[third_page + 200] ^= 0x10;
    let error = decode(&flipped).expect_err("an error");
    assert_eq!(error.kind, ErrorKind::Failed);
    assert!(error.message.contains("checksum"), "{}", error.message);

    // Junk where a page should start.
    let mut junk = file[..third_page].to_vec();
    junk.extend_from_slice(&[0u8; 64]);
    junk.extend_from_slice(&file[third_page..]);
    assert_eq!(kind(&junk), ErrorKind::Failed);

    // An Opus header with nothing after it that is a comment header.
    let mut headless = Vec::new();
    {
        let mut writer = PacketWriter::new(&mut headless);
        writer.write_packet(pages(&file)[0][28..].to_vec(), 1, PacketWriteEndInfo::EndPage, 0).unwrap();
        writer.write_packet(vec![0xfc, 0xff, 0xfe], 1, PacketWriteEndInfo::EndStream, 960).unwrap();
    }
    assert_eq!(kind(&headless), ErrorKind::Failed);
}

#[test]
fn a_file_cut_between_pages_is_read_as_far_as_it_goes() {
    // Indistinguishable from a file whose writer never marked the last page, so it is not an error.
    let length = 100_000;
    let file = ogg_opus(&Source::new(1, length), &mut support::sweep(length));
    let two_pages_of_audio: usize = pages(&file)[..4].iter().map(|page| page.len()).sum();
    assert_eq!(frames(&file[..two_pages_of_audio]), 100 * PACKET_FRAMES - 312);
}
