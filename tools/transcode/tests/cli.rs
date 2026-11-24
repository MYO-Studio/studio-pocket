// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

//! The program as the desktop app runs it: arguments, exit codes, the lines on standard output,
//! and the MP3 it writes. The inputs are made here with libopus's encoder (`support`).

mod support;

use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};
use std::time::Instant;

use support::{ogg_opus, parse_mp3, Mp3, Source};

const PROGRAM: &str = env!("CARGO_BIN_EXE_studio-pocket-transcode");

/// A folder of its own for one test, emptied first.
fn folder(test: &str) -> PathBuf {
    let folder = Path::new(env!("CARGO_TARGET_TMPDIR")).join(format!("cli-{test}"));
    let _ = fs::remove_dir_all(&folder);
    fs::create_dir_all(&folder).unwrap();
    folder
}

fn run(arguments: &[&Path]) -> Output {
    Command::new(PROGRAM).args(arguments).output().expect("the program runs")
}

fn text(bytes: &[u8]) -> &str {
    std::str::from_utf8(bytes).expect("UTF-8")
}

/// Standard error is one line that names the program.
fn assert_one_line_of_error(output: &Output) {
    let error = text(&output.stderr);
    assert!(error.starts_with("studio-pocket-transcode: ") && error.ends_with('\n') && error.matches('\n').count() == 1, "{error:?}");
}

/// Checks standard output against the protocol and returns the progress lines' counts of bytes
/// read and the size that the `done` line reports.
fn protocol(output: &Output, input_size: u64) -> (Vec<u64>, u64) {
    let stdout = text(&output.stdout);
    assert!(stdout.ends_with('\n'), "{stdout:?}");
    let lines: Vec<&str> = stdout.lines().collect();
    let (done, progress) = lines.split_last().expect("at least the done line");
    assert!(!progress.is_empty(), "a progress line comes first: {stdout:?}");
    let mut consumed = Vec::new();
    for line in progress {
        let fields: Vec<&str> = line.split(' ').collect();
        assert!(fields.len() == 3 && fields[0] == "progress", "{line:?}");
        assert_eq!(fields[2].parse::<u64>().unwrap(), input_size, "{line:?}");
        consumed.push(fields[1].parse::<u64>().unwrap());
    }
    assert!(consumed.windows(2).all(|pair| pair[0] <= pair[1]) && *consumed.last().unwrap() <= input_size, "{consumed:?}");
    let size = done.strip_prefix("done ").unwrap_or_else(|| panic!("the last line is {done:?}")).parse::<u64>().unwrap();
    (consumed, size)
}

/// Converts `source` and checks everything that does not depend on the channel count.
fn convert(test: &str, source: &Source) -> Mp3 {
    let folder = folder(test);
    let (input, output) = (folder.join("in.opus"), folder.join("out.mp3.part"));
    let opus = ogg_opus(source, &mut support::sweep(source.frames));
    fs::write(&input, &opus).unwrap();

    let result = run(&[&input, &output]);
    assert!(result.status.success(), "{result:?}");
    assert!(result.stderr.is_empty(), "{result:?}");
    let (_, size) = protocol(&result, opus.len() as u64);
    let bytes = fs::read(&output).unwrap();
    assert_eq!(size, bytes.len() as u64, "done reports the file's size");
    assert_eq!(fs::read_dir(&folder).unwrap().count(), 2, "nothing is written but the output");

    let mp3 = parse_mp3(&bytes);
    assert_eq!(mp3.bitrates_kbps.iter().copied().collect::<Vec<_>>(), [128], "constant 128 kbps");
    assert_eq!(mp3.sample_rates.iter().copied().collect::<Vec<_>>(), [48_000]);
    // Constant bitrate at 48 kHz: every frame is 384 bytes, none padded.
    assert_eq!(bytes.len() as u64, mp3.frame_count * 384);

    let info = mp3.info.as_ref().expect("the file starts with an Info frame");
    assert_eq!(info.tag, "Info", "constant bitrate");
    assert!(info.encoder.starts_with("LAME"), "{info:?}");
    assert_eq!(info.frames, mp3.frame_count - 1);
    assert_eq!(info.bytes, bytes.len() as u64);
    // A decoder that reads the Info frame plays exactly the audio that went in...
    assert_eq!(mp3.samples(), source.frames, "{info:?}");
    // ...and one that does not is out by the encoder's delay and padding: under three frames.
    assert!(
        mp3.plain_samples() >= source.frames && mp3.plain_samples() - source.frames < 3 * 1152,
        "{} frames for {} samples",
        mp3.frame_count,
        source.frames
    );
    mp3
}

#[test]
fn version() {
    let result = Command::new(PROGRAM).arg("--version").output().unwrap();
    assert!(result.status.success() && result.stderr.is_empty(), "{result:?}");
    assert_eq!(text(&result.stdout), format!("studio-pocket-transcode {}\n", env!("CARGO_PKG_VERSION")));
    let version: Vec<&str> = env!("CARGO_PKG_VERSION").split('.').collect();
    assert!(version.len() == 3 && version.iter().all(|part| part.parse::<u32>().is_ok()), "a plain semantic version");
}

#[test]
fn wrong_arguments_are_a_usage_error() {
    let folder = folder("usage");
    let existing = folder.join("in.opus");
    fs::write(&existing, ogg_opus(&Source::seconds(1, 0.1), &mut support::sweep(4_800))).unwrap();
    let cases: [&[&str]; 5] = [&[], &["--help"], &["-V"], &["only-one-path"], &["a", "b", "c"]];
    for arguments in cases {
        let result = Command::new(PROGRAM).args(arguments).current_dir(&folder).output().unwrap();
        assert_eq!(result.status.code(), Some(2), "{arguments:?}: {result:?}");
        assert!(result.stdout.is_empty(), "{arguments:?}: {result:?}");
        assert_one_line_of_error(&result);
    }
    assert_eq!(fs::read_dir(&folder).unwrap().count(), 1, "nothing was written");
}

#[test]
fn mono() {
    // 2.5 s and a bit: not a whole number of Opus packets or of MP3 frames.
    let mp3 = convert("mono", &Source::new(1, 120_123));
    assert_eq!(mp3.channel_modes.iter().copied().collect::<Vec<_>>(), [3], "every frame is mono");
}

#[test]
fn stereo() {
    let mp3 = convert("stereo", &Source::new(2, 150_001));
    assert_eq!(mp3.channel_modes.iter().copied().collect::<Vec<_>>(), [1], "every frame is joint stereo");
}

#[test]
fn pre_skip_and_end_trimming_set_the_length() {
    // The same audio behind three different pre-skips, with the last packet mostly padding, and
    // once with page positions that jump ahead: `convert` checks each is the same length to the sample.
    for (name, pre_skip, position_jump) in
        [("skip-default", None, None), ("skip-3840", Some(3_840), None), ("skip-6000", Some(6_000), None), ("skip-jump", None, Some((1, 2_312)))]
    {
        convert(name, &Source { pre_skip, position_jump, ..Source::new(2, 96_050) });
    }
    // A stream of one sample still makes a playable file.
    convert("one-sample", &Source::new(1, 1));
}

#[test]
fn an_input_that_is_not_ogg_opus_exits_3_and_leaves_the_output_path_alone() {
    let folder = folder("not-opus");
    let output = folder.join("out.mp3.part");
    let surround = Source { mapping_family: 1, ..Source::new(2, 4_800) };
    let cases: [(&str, Vec<u8>); 4] = [
        ("mp3", [0xff, 0xfb, 0x94, 0x00].repeat(500)),
        ("text", b"OggS? No: just text that happens to start like that.".to_vec()),
        ("empty", Vec::new()),
        ("surround", ogg_opus(&surround, &mut support::sweep(4_800))),
    ];
    for (name, bytes) in &cases {
        let input = folder.join(name);
        fs::write(&input, bytes).unwrap();
        let result = run(&[&input, &output]);
        assert_eq!(result.status.code(), Some(3), "{name}: {result:?}");
        assert!(result.stdout.is_empty(), "{name}: {result:?}");
        assert_one_line_of_error(&result);
        assert!(!output.exists(), "{name}: no output file");
    }
    // The caller converts such a file another way, to the same path: whatever is there stays.
    fs::write(&output, b"someone else's").unwrap();
    let result = run(&[&folder.join("mp3"), &output]);
    assert_eq!(result.status.code(), Some(3));
    assert_eq!(fs::read(&output).unwrap(), b"someone else's");
}

#[test]
fn a_truncated_stream_exits_4_and_removes_the_partial_output() {
    let folder = folder("truncated");
    let (input, output) = (folder.join("in.opus"), folder.join("out.mp3.part"));
    let opus = ogg_opus(&Source::seconds(2, 20.0), &mut support::sweep(20 * 48_000));
    // Three quarters of the file: the cut falls inside a page.
    fs::write(&input, &opus[..opus.len() * 3 / 4]).unwrap();
    // A file from an earlier run is replaced, then removed with the rest.
    fs::write(&output, b"stale").unwrap();

    let result = run(&[&input, &output]);
    assert_eq!(result.status.code(), Some(4), "{result:?}");
    assert_one_line_of_error(&result);
    assert!(text(&result.stderr).contains("incomplete"), "{result:?}");
    assert!(!output.exists(), "the partial output is removed");
    // It had started: standard output holds progress lines and no done line.
    let stdout = text(&result.stdout);
    assert!(stdout.starts_with("progress ") && !stdout.contains("done"), "{stdout:?}");
}

#[test]
fn files_that_cannot_be_opened_exit_4() {
    let folder = folder("io");
    let input = folder.join("in.opus");
    fs::write(&input, ogg_opus(&Source::seconds(1, 0.5), &mut support::sweep(24_000))).unwrap();

    let result = run(&[&folder.join("missing.opus"), &folder.join("out.mp3.part")]);
    assert_eq!(result.status.code(), Some(4), "{result:?}");
    assert_one_line_of_error(&result);

    let result = run(&[&input, &folder.join("no-such-folder").join("out.mp3.part")]);
    assert_eq!(result.status.code(), Some(4), "{result:?}");
    assert_one_line_of_error(&result);

    assert_eq!(fs::read_dir(&folder).unwrap().count(), 1, "nothing was written");
}

#[test]
fn converting_a_file_onto_itself_is_refused() {
    let folder = folder("same");
    let input = folder.join("in.opus");
    let opus = ogg_opus(&Source::seconds(1, 0.5), &mut support::sweep(24_000));
    fs::write(&input, &opus).unwrap();
    // The same file by another spelling of its path.
    let result = run(&[&input, &folder.join(".").join("in.opus")]);
    assert_eq!(result.status.code(), Some(2), "{result:?}");
    assert_one_line_of_error(&result);
    assert_eq!(fs::read(&input).unwrap(), opus, "the input is untouched");
}

#[test]
fn progress_is_reported_at_most_about_four_times_a_second() {
    let folder = folder("progress");
    let (input, output) = (folder.join("in.opus"), folder.join("out.mp3.part"));
    // Long enough that the conversion outlasts several progress intervals in an unoptimised build
    // and at least reaches the second one in an optimised build on a fast machine.
    let source = Source::seconds(2, 90.0);
    let opus = ogg_opus(&source, &mut support::sweep(source.frames));
    fs::write(&input, &opus).unwrap();

    let started = Instant::now();
    let result = run(&[&input, &output]);
    let elapsed = started.elapsed().as_secs_f64();
    assert!(result.status.success(), "{result:?}");
    let (consumed, size) = protocol(&result, opus.len() as u64);
    assert_eq!(size, fs::metadata(&output).unwrap().len());
    // The first line is written before any audio is read: only the headers have been.
    assert!(consumed[0] < 1_000, "{consumed:?}");
    // One line at the start and then one per 250 ms at most.
    assert!(consumed.len() as f64 <= elapsed / 0.25 + 1.0, "{} progress lines in {elapsed:.2} s", consumed.len());
    // And it does report: a conversion this long cannot have passed with only the first line.
    assert!(elapsed < 1.5 || consumed.len() >= 2, "{} progress line(s) in {elapsed:.2} s", consumed.len());
}
