# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Ashworth Digital Ltd
# This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
# commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

"""Minimal MediaTek preloader probe over the CDC serial port: handshake, then GET_HW_CODE (0xFD).
Read-only; the preloader answers with its hardware code. Used to tell a device problem from an
mtkclient-on-macOS problem."""
import sys, time, glob
import serial

ports = sys.argv[1:] or glob.glob('/dev/cu.usbmodem*')
if not ports:
    print('no /dev/cu.usbmodem* port'); sys.exit(1)
port = ports[0]
print('port', port)
s = serial.Serial(port, 115200, timeout=0.3)
s.reset_input_buffer()

def hx(b): return b.hex() if b else '(none)'

# Handshake: send A0 0A 50 05, device replies with the complement 5F F5 AF FA, byte by byte.
ok = True
for tx, rx in ((0xA0, 0x5F), (0x0A, 0xF5), (0x50, 0xAF), (0x05, 0xFA)):
    got = None
    for attempt in range(40):
        s.write(bytes([tx]))
        r = s.read(1)
        if r == bytes([rx]):
            got = r; break
        time.sleep(0.05)
    print(f'handshake tx {tx:02x} -> {hx(got)} (expected {rx:02x})')
    if got is None:
        ok = False; break
if not ok:
    print('handshake failed'); sys.exit(2)
# GET_HW_CODE: device echoes 0xFD, then 2 bytes hw code, 2 bytes status.
s.write(b'\xfd')
echo = s.read(1)
code = s.read(2)
status = s.read(2)
print('FD echo', hx(echo), 'hwcode', hx(code), 'status', hx(status))
# GET_HW_SW_VER 0xFC: echo, hw sub code(2), hw ver(2), sw ver(2), status(2)
s.write(b'\xfc')
print('FC echo', hx(s.read(1)), 'subcode', hx(s.read(2)), 'hwver', hx(s.read(2)), 'swver', hx(s.read(2)), 'status', hx(s.read(2)))
s.close()
