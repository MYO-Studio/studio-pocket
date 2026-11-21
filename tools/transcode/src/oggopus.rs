// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

//! Reads an Ogg Opus file (RFC 7845) a page at a time and decodes it with libopus to 48 kHz
//! float PCM, one packet per call. Nothing is kept beyond the current page and packet.
//!
//! What is honoured from the stream: the pre-skip and the output gain of the `OpusHead` header,
//! and the granule position of the last page, which says where the audio really ends (the last
//! packet usually decodes to more samples than were encoded).
//!
//! Only channel mapping family 0 (mono or stereo, one Opus stream) is read. Other streams in the
//! same file are skipped, and reading stops at the end of the Opus stream: whatever follows it in
//! the file is ignored.

use std::ffi::{c_int, CStr};
use std::io::{self, Read};

use ogg::reading::{BasePacketReader, OggReadError, PageParser};
use ogg::Packet;
use opusic_sys as opus;

use crate::{Error, Result};

/// Opus always decodes to 48 kHz here, whatever rate the original had.
pub const SAMPLE_RATE: u32 = 48_000;

/// The longest Opus packet is 120 ms: 5760 samples per channel at 48 kHz.
const MAX_FRAME: usize = 5760;

/// The identification header: the first packet of an Ogg Opus stream.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OpusHead {
    pub channels: u8,
    /// Samples (at 48 kHz) to drop from the start of the decoded audio.
    pub pre_skip: u16,
    /// The rate of the audio before it was encoded. Informational: decoding is at 48 kHz.
    pub input_sample_rate: u32,
    /// Gain to apply when decoding, in dB as a Q7.8 fixed-point number.
    pub output_gain: i16,
    pub mapping_family: u8,
}

impl OpusHead {
    pub const MAGIC: &'static [u8; 8] = b"OpusHead";

    pub fn parse(packet: &[u8]) -> Result<OpusHead> {
        if !packet.starts_with(Self::MAGIC) {
            return Err(Error::unsupported("the input is Ogg but its first stream is not Opus"));
        }
        if packet.len() < 19 {
            return Err(Error::failed("the Opus header is too short"));
        }
        let version = packet[8];
        if version & 0xf0 != 0 {
            return Err(Error::unsupported(format!("Opus header version {version} is not supported")));
        }
        let head = OpusHead {
            channels: packet[9],
            pre_skip: u16::from_le_bytes([packet[10], packet[11]]),
            input_sample_rate: u32::from_le_bytes([packet[12], packet[13], packet[14], packet[15]]),
            output_gain: i16::from_le_bytes([packet[16], packet[17]]),
            mapping_family: packet[18],
        };
        if head.mapping_family != 0 {
            return Err(Error::unsupported(format!(
                "Opus channel mapping family {} ({} channels) is not supported, only mono and stereo",
                head.mapping_family, head.channels
            )));
        }
        if head.channels != 1 && head.channels != 2 {
            return Err(Error::unsupported(format!("an Opus stream with {} channels is not supported, only mono and stereo", head.channels)));
        }
        Ok(head)
    }
}

/// What the header of an Ogg page says about it.
struct PageInfo {
    serial: u32,
    /// The page is the last of its stream.
    last: bool,
    /// For Opus: how many samples have been decoded when the last packet that ends on this page
    /// has been, counted from the start of the stream and including the pre-skip. `None` when no
    /// packet ends on the page.
    granule: Option<u64>,
}

struct Decoder(*mut opus::OpusDecoder);

impl Drop for Decoder {
    fn drop(&mut self) {
        // SAFETY: the pointer came from opus_decoder_create and is destroyed once.
        unsafe { opus::opus_decoder_destroy(self.0) }
    }
}

fn opus_error(code: c_int) -> String {
    // SAFETY: opus_strerror returns a static, NUL-terminated string for any code.
    unsafe { CStr::from_ptr(opus::opus_strerror(code)) }.to_string_lossy().into_owned()
}

pub struct OggOpusDecoder<R: Read> {
    input: R,
    consumed: u64,
    pages: BasePacketReader,
    serial: u32,
    head: OpusHead,
    tags_seen: bool,
    /// The last page read of the Opus stream; the packets being handed out are its.
    page: PageInfo,
    /// The Opus stream's last page has been read: no more pages are.
    last_page_read: bool,
    ended: bool,
    decoder: Decoder,
    /// One decoded packet, interleaved.
    pcm: Vec<f32>,
    clip_state: [f32; 2],
    /// What is left of the pre-skip.
    to_skip: u64,
    /// The position of the last page that had one and has been decoded to its end; zero before.
    granule_base: u64,
    /// Samples decoded since then, per channel.
    since_base: u64,
    packets: u64,
}

impl<R: Read> OggOpusDecoder<R> {
    /// Reads up to and including the Opus identification header. An input that is not Ogg, or has
    /// no Opus stream this program reads, is `ErrorKind::Unsupported`.
    pub fn open(input: R) -> Result<Self> {
        let mut input = input;
        let mut consumed = 0u64;
        let mut pages = BasePacketReader::new();
        let mut first_page = true;
        let (serial, head) = loop {
            if let Some(packet) = pages.read_packet() {
                // Every stream's first page comes before any other page, so the first packet that
                // does not open a stream ends the search.
                if !packet.first_in_stream() {
                    return Err(Error::unsupported("the input is Ogg but has no Opus stream"));
                }
                if packet.data.starts_with(OpusHead::MAGIC) {
                    break (packet.stream_serial(), OpusHead::parse(&packet.data)?);
                }
                continue;
            }
            match read_page(&mut input, &mut consumed, &mut pages, first_page)? {
                Some(_) => first_page = false,
                None => return Err(Error::unsupported("the input is Ogg but has no Opus stream")),
            }
        };

        let channels = usize::from(head.channels);
        let mut status: c_int = 0;
        // SAFETY: plain FFI call; the result is checked before use.
        let raw = unsafe { opus::opus_decoder_create(SAMPLE_RATE as i32, channels as c_int, &mut status) };
        if raw.is_null() || status != opus::OPUS_OK {
            return Err(Error::failed(format!("could not create the Opus decoder: {}", opus_error(status))));
        }
        let decoder = Decoder(raw);
        if head.output_gain != 0 {
            // SAFETY: OPUS_SET_GAIN takes one opus_int32; every i16 is in its range.
            let status = unsafe { opus::opus_decoder_ctl(decoder.0, opus::OPUS_SET_GAIN_REQUEST, c_int::from(head.output_gain)) };
            if status != opus::OPUS_OK {
                return Err(Error::failed(format!("could not set the Opus output gain: {}", opus_error(status))));
            }
        }

        Ok(OggOpusDecoder {
            input,
            consumed,
            pages,
            serial,
            to_skip: u64::from(head.pre_skip),
            head,
            tags_seen: false,
            page: PageInfo { serial, last: false, granule: None },
            last_page_read: false,
            ended: false,
            decoder,
            pcm: vec![0.0; MAX_FRAME * channels],
            clip_state: [0.0; 2],
            granule_base: 0,
            since_base: 0,
            packets: 0,
        })
    }

    pub fn head(&self) -> &OpusHead {
        &self.head
    }

    pub fn channels(&self) -> usize {
        usize::from(self.head.channels)
    }

    /// Bytes of the input read so far.
    pub fn bytes_consumed(&self) -> u64 {
        self.consumed
    }

    /// The next piece of audio: interleaved samples at 48 kHz, at most 120 ms of them. `None` at
    /// the end of the Opus stream.
    pub fn next_chunk(&mut self) -> Result<Option<&[f32]>> {
        match self.advance()? {
            Some((from, to)) => Ok(Some(&self.pcm[from..to])),
            None => Ok(None),
        }
    }

    /// Decodes packets until one yields audio that is kept; returns its range in `self.pcm`.
    fn advance(&mut self) -> Result<Option<(usize, usize)>> {
        loop {
            if self.ended {
                return Ok(None);
            }
            let Some(packet) = self.pages.read_packet() else {
                if self.last_page_read {
                    self.ended = true;
                    return Ok(None);
                }
                match read_page(&mut self.input, &mut self.consumed, &mut self.pages, false)? {
                    Some(page) if page.serial == self.serial => {
                        self.last_page_read = page.last;
                        self.page = page;
                    }
                    Some(_) => {}
                    // The file ends on a page boundary without the stream's last page having been
                    // marked. Some writers leave the mark out, so what was read is the audio.
                    None => {
                        self.ended = true;
                        return Ok(None);
                    }
                }
                continue;
            };
            if packet.stream_serial() != self.serial {
                continue;
            }
            if !self.tags_seen {
                if !packet.data.starts_with(b"OpusTags") {
                    return Err(Error::failed("the Opus stream has no comment header"));
                }
                self.tags_seen = true;
                continue;
            }
            if let Some(range) = self.decode(&packet)? {
                return Ok(Some(range));
            }
        }
    }

    fn decode(&mut self, packet: &Packet) -> Result<Option<(usize, usize)>> {
        self.packets += 1;
        if packet.data.is_empty() {
            return Ok(None);
        }
        let channels = self.channels();
        let length = i32::try_from(packet.data.len()).map_err(|_| Error::failed(format!("Opus packet {} is too large", self.packets)))?;
        // SAFETY: `pcm` holds MAX_FRAME samples per channel, the most one packet decodes to.
        let frames = unsafe { opus::opus_decode_float(self.decoder.0, packet.data.as_ptr(), length, self.pcm.as_mut_ptr(), MAX_FRAME as c_int, 0) };
        if frames < 0 {
            return Err(Error::failed(format!("Opus packet {} could not be decoded: {}", self.packets, opus_error(frames))));
        }
        // Float decoding does not limit the signal; an MP3 decoder would clip it hard. This is the
        // limiter the Opus tools use, and it leaves samples within full scale untouched.
        // SAFETY: `frames` frames of `channels` samples were just written; the state has two slots.
        unsafe { opus::opus_pcm_soft_clip(self.pcm.as_mut_ptr(), frames, channels as c_int, self.clip_state.as_mut_ptr()) };

        let frames = frames as u64;
        self.since_base += frames;

        let skip = self.to_skip.min(frames);
        self.to_skip -= skip;

        let mut keep = frames;
        if let Some(granule) = self.page.granule {
            if self.page.last {
                // The last page's position says where the audio ends; what is decoded past it is
                // the encoder's padding of the final packet. It is measured from the page before,
                // not from the start of the stream: real files (FFmpeg-made ones among them) have
                // positions that jump ahead of the audio part-way through, and counting from the
                // start would then never trim.
                let excess = (self.granule_base + self.since_base).saturating_sub(granule);
                keep = frames.saturating_sub(excess);
            }
            if packet.last_in_page() {
                self.granule_base = granule;
                self.since_base = 0;
            }
        }
        if keep <= skip {
            return Ok(None);
        }
        Ok(Some((skip as usize * channels, keep as usize * channels)))
    }
}

/// Reads one page into `pages`. `Ok(None)` when the input ends exactly at a page boundary.
fn read_page<R: Read>(input: &mut R, consumed: &mut u64, pages: &mut BasePacketReader, first: bool) -> Result<Option<PageInfo>> {
    const TRUNCATED: &str = "the file ends in the middle of an Ogg page (is it incomplete?)";
    let not_ogg = || Error::unsupported("the input is not an Ogg file");

    let offset = *consumed;
    let mut header = [0u8; 27];
    let got = read_up_to(input, &mut header).map_err(read_error)?;
    *consumed += got as u64;
    if got == 0 && !first {
        return Ok(None);
    }
    let prefix = got.min(4);
    if header[..prefix] != b"OggS"[..prefix] || (first && got < 4) {
        return Err(if first { not_ogg() } else { Error::failed(format!("the Ogg page structure is broken at byte {offset}")) });
    }
    if got < header.len() {
        return Err(Error::failed(TRUNCATED));
    }
    let granule = u64::from_le_bytes(header[6..14].try_into().expect("8 bytes"));
    let info = PageInfo {
        serial: u32::from_le_bytes(header[14..18].try_into().expect("4 bytes")),
        last: header[5] & 0x04 != 0,
        // All ones means no packet ends on the page; positions are never that large otherwise.
        granule: (granule < 1 << 63).then_some(granule),
    };

    let ogg_error = |error: OggReadError| match error {
        OggReadError::InvalidStreamStructVer(_) if first => not_ogg(),
        OggReadError::HashMismatch(..) => Error::failed(format!("the Ogg page at byte {offset} is corrupt (checksum mismatch)")),
        OggReadError::ReadError(error) => read_error(error),
        other => Error::failed(format!("the Ogg stream is invalid at byte {offset}: {other}")),
    };
    let mut fill =
        |buffer: &mut [u8], consumed: &mut u64| -> Result<()> {
            input.read_exact(buffer).map_err(|error| {
                if error.kind() == io::ErrorKind::UnexpectedEof {
                    Error::failed(TRUNCATED)
                } else {
                    read_error(error)
                }
            })?;
            *consumed += buffer.len() as u64;
            Ok(())
        };

    let (mut parser, segment_count) = PageParser::new(header).map_err(&ogg_error)?;
    let mut segments = vec![0u8; segment_count];
    fill(&mut segments, consumed)?;
    let mut body = vec![0u8; parser.parse_segments(segments)];
    fill(&mut body, consumed)?;
    let page = parser.parse_packet_data(body).map_err(&ogg_error)?;
    pages.push_page(page).map_err(&ogg_error)?;
    Ok(Some(info))
}

fn read_error(error: io::Error) -> Error {
    Error::failed(format!("could not read the input: {error}"))
}

/// Fills `buffer` unless the input ends first; returns how many bytes were read.
fn read_up_to<R: Read>(input: &mut R, buffer: &mut [u8]) -> io::Result<usize> {
    let mut filled = 0;
    while filled < buffer.len() {
        match input.read(&mut buffer[filled..]) {
            Ok(0) => break,
            Ok(n) => filled += n,
            Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
            Err(error) => return Err(error),
        }
    }
    Ok(filled)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ErrorKind;

    fn head(channels: u8, family: u8) -> Vec<u8> {
        let mut packet = b"OpusHead".to_vec();
        packet.push(1);
        packet.push(channels);
        packet.extend_from_slice(&312u16.to_le_bytes());
        packet.extend_from_slice(&44_100u32.to_le_bytes());
        packet.extend_from_slice(&(-256i16).to_le_bytes());
        packet.push(family);
        packet
    }

    #[test]
    fn parses_the_identification_header() {
        let parsed = OpusHead::parse(&head(2, 0)).unwrap();
        assert_eq!(parsed, OpusHead { channels: 2, pre_skip: 312, input_sample_rate: 44_100, output_gain: -256, mapping_family: 0 });
        assert_eq!(OpusHead::parse(&head(1, 0)).unwrap().channels, 1);
    }

    #[test]
    fn refuses_what_it_does_not_read() {
        // Surround (family 1), a family 0 header with an impossible channel count, another
        // codec, and a header from a future, incompatible version of the format.
        for packet in [head(6, 1), head(3, 0), head(0, 0), b"\x01vorbis\0\0\0\0\x02\x44\xac\0\0".to_vec()] {
            assert_eq!(OpusHead::parse(&packet).unwrap_err().kind, ErrorKind::Unsupported, "{packet:?}");
        }
        let mut future = head(2, 0);
        future[8] = 0x10;
        assert_eq!(OpusHead::parse(&future).unwrap_err().kind, ErrorKind::Unsupported);
        // A minor version bump stays readable.
        future[8] = 0x0f;
        assert!(OpusHead::parse(&future).is_ok());
    }

    #[test]
    fn a_cut_off_header_is_a_failure_not_a_fallback() {
        assert_eq!(OpusHead::parse(&head(2, 0)[..12]).unwrap_err().kind, ErrorKind::Failed);
    }
}
