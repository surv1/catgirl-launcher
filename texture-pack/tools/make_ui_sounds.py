"""Catgirl menu sounds (made from scratch): the bloom when the menu opens, a soft pop when you
pick something, a two-note tick for switches, and a gentle close. Writes mono .ogg files.
Usage: python3 make_ui_sounds.py <out_dir>   (needs numpy, scipy, ffmpeg)"""
import os
import sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_sounds as ms  # noqa: E402

SR = ms.SR


def bloom():
    out = np.zeros(int(SR * 0.9))
    w = ms.noise_band(0.45, 2500, 9000) * np.sin(np.pi * np.linspace(0, 1, int(SR * 0.45))) ** 2 * 0.18
    ms.place(out, w, 0.0)
    for i, f in enumerate((783.99, 1046.5, 1318.5, 1567.98)):  # G C E G, soft and quick
        ms.place(out, 0.42 * ms.bell(f, 0.8, 0.35, bright=0.5), 0.02 + i * 0.055)
    ms.place(out, ms.sparkles(0.5, 14, 4000, 9000, 0.12), 0.1)
    return ms.fade_out(ms.normalize(out, -6.0), 0.15)


def pop():
    t = ms.t_axis(0.12)
    x = np.sin(2 * np.pi * (900 + 900 * t / 0.12) * t) * ms.env(len(t), 0.002, 0.025)
    return ms.fade_out(ms.normalize(x, -9.0), 0.02)


def toggle(up):
    out = np.zeros(int(SR * 0.22))
    a, b = (1318.5, 1760.0) if up else (1318.5, 987.77)
    ms.place(out, 0.5 * ms.bell(a, 0.15, 0.04, bright=0.3), 0.0)
    ms.place(out, 0.5 * ms.bell(b, 0.18, 0.05, bright=0.3), 0.06)
    return ms.fade_out(ms.normalize(out, -9.0), 0.03)


def close():
    out = np.zeros(int(SR * 0.45))
    for i, f in enumerate((1318.5, 1046.5, 783.99)):
        ms.place(out, 0.4 * ms.bell(f, 0.35, 0.12, bright=0.4), i * 0.05)
    return ms.fade_out(ms.normalize(out, -8.0), 0.08)


if __name__ == '__main__':
    out = sys.argv[1]
    ms.write_ogg(os.path.join(out, 'bloom.ogg'), bloom())
    ms.write_ogg(os.path.join(out, 'pop.ogg'), pop())
    ms.write_ogg(os.path.join(out, 'on.ogg'), toggle(True))
    ms.write_ogg(os.path.join(out, 'off.ogg'), toggle(False))
    ms.write_ogg(os.path.join(out, 'close.ogg'), close())
