"""Catgirl Pack: every sound is synthesised here from scratch (no recordings, nobody else's audio).
Writes mono .ogg files into <out_dir> (needs numpy, scipy and ffmpeg with libvorbis).
Usage: python3 make_sounds.py <out_dir>"""
import os
import subprocess
import sys
import tempfile
import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, sosfilt

SR = 44100
rng = np.random.default_rng(7)


def t_axis(sec):
    return np.arange(int(SR * sec)) / SR


def env(n, attack, decay_tau):
    t = np.arange(n) / SR
    a = np.clip(t / max(attack, 1e-4), 0, 1)
    return a * np.exp(-np.maximum(t - attack, 0) / decay_tau)


def bell(freq, sec, tau, bright=1.0):
    """A small glassy bell: a few slightly inharmonic partials, the high ones dying first."""
    t = t_axis(sec)
    out = np.zeros_like(t)
    for ratio, amp, tk in ((1.0, 1.0, 1.0), (2.0, 0.5 * bright, 0.6), (2.76, 0.35 * bright, 0.4),
                           (5.40, 0.18 * bright, 0.25), (8.93, 0.08 * bright, 0.15)):
        out += amp * np.sin(2 * np.pi * freq * ratio * t + rng.uniform(0, 6.28)) * env(len(t), 0.002, tau * tk)
    return out


def noise_band(sec, lo, hi, order=2):
    sos = butter(order, [lo, hi], btype='band', fs=SR, output='sos')
    return sosfilt(sos, rng.standard_normal(int(SR * sec)))


def place(buf, clip, at):
    i = int(at * SR)
    end = min(len(buf), i + len(clip))
    buf[i:end] += clip[:end - i]


def sparkles(sec, count, lo=3000, hi=9000, level=0.25):
    out = np.zeros(int(SR * sec))
    for _ in range(count):
        f = rng.uniform(lo, hi)
        place(out, level * rng.uniform(0.4, 1) * bell(f, 0.25, 0.05, bright=0.3), rng.uniform(0, sec - 0.25))
    return out


def meow(sec=1.0, f0_lo=420, f0_hi=720):
    """A small cat 'mi-aa-ow': a buzzy voice through three moving mouth resonances (formants)."""
    n = int(SR * sec)
    u = np.linspace(0, 1, n)
    # pitch rises then falls, with a little wobble
    f0 = f0_lo + (f0_hi - f0_lo) * np.sin(np.pi * np.clip(u * 1.15, 0, 1)) ** 1.2
    f0 = f0 * (1 + 0.012 * np.sin(2 * np.pi * 6.5 * u * sec))
    phase = np.cumsum(f0) / SR
    src = 2 * (phase % 1) - 1                     # sawtooth voice
    src += 0.08 * rng.standard_normal(n)          # breathiness
    # formants: "i" -> "a" -> "u"
    keys = np.array([0.0, 0.25, 0.6, 1.0])
    F = [np.interp(u, keys, [350, 650, 900, 420]),
         np.interp(u, keys, [2300, 1700, 1350, 850]),
         np.interp(u, keys, [3200, 2900, 2700, 2500])]
    B = [90, 130, 200]
    G = [1.0, 0.6, 0.25]
    out = np.zeros(n)
    for fk, bw, g in zip(F, B, G):
        r = np.exp(-np.pi * bw / SR)
        a1 = -2 * r * np.cos(2 * np.pi * fk / SR)
        a2 = r * r
        y1 = y2 = 0.0
        y = np.empty(n)
        for i in range(n):  # time-varying two-pole resonator
            yi = (1 - r) * src[i] - a1[i] * y1 - a2 * y2
            y[i] = yi; y2 = y1; y1 = yi
        out += g * y
    a = np.interp(u, [0, 0.06, 0.7, 1.0], [0, 1, 0.8, 0]) ** 1.3
    return out * a


def normalize(x, peak_db=-1.0):
    x = x - np.mean(x)
    pk = np.max(np.abs(x)) or 1
    return x / pk * 10 ** (peak_db / 20)


def fade_out(x, sec=0.05):
    n = min(len(x), int(SR * sec))
    x[-n:] *= np.linspace(1, 0, n)
    return x


def totem():
    sec = 2.6
    out = np.zeros(int(SR * sec))
    # magic whoosh swelling up
    w = noise_band(1.0, 1500, 6000) * np.sin(np.pi * np.linspace(0, 1, SR)) ** 2 * 0.35
    place(out, w, 0.0)
    # rising chime arpeggio (C major 9 shape), then a sparkly ring
    notes = [523.25, 659.25, 783.99, 1046.5, 1318.5, 1567.98]
    for i, f in enumerate(notes):
        place(out, 0.55 * bell(f, 1.8, 0.7), 0.05 + i * 0.075)
    place(out, 0.5 * bell(2093.0, 2.0, 0.9), 0.5)
    place(out, 0.35 * bell(1567.98 * 1.5, 1.6, 0.6), 0.55)
    place(out, sparkles(2.0, 60), 0.15)
    # the little cat meowing in the background, quietly, after the chime
    m = meow(0.85, 480, 760)
    m = m / np.max(np.abs(m)) * 0.3
    place(out, m, 0.95)
    return fade_out(normalize(out, -1.5), 0.3)


def sweep(variant):
    sec = 0.45
    n = int(SR * sec)
    u = np.linspace(0, 1, n)
    # filtered noise whose pitch swoops up then down = "fwoosh"
    centre = np.interp(u, [0, 0.35, 1], [900, 3800, 1600]) * (1 + 0.15 * variant)
    noise = rng.standard_normal(n)
    out = np.zeros(n)
    blocks = 30
    for b in range(blocks):
        s, e = b * n // blocks, (b + 1) * n // blocks
        c = centre[(s + e) // 2]
        sos = butter(2, [c * 0.6, min(c * 1.6, 18000)], btype='band', fs=SR, output='sos')
        out[s:e] = sosfilt(sos, noise[max(0, s - 600):e])[-(e - s):]
    out *= np.sin(np.pi * u) ** 1.5
    # a light pink "shing"
    place(out, 0.25 * bell(2637.0 * (1 + 0.06 * variant), 0.4, 0.12, bright=0.6), 0.08)
    return fade_out(normalize(out, -2.0))


def strong(variant):
    sec = 0.4
    out = np.zeros(int(SR * sec))
    t = t_axis(0.25)
    thump = np.sin(2 * np.pi * (140 - 200 * t) * t) * env(len(t), 0.002, 0.05)
    place(out, thump, 0)
    place(out, 0.6 * noise_band(0.12, 800, 5000) * env(int(SR * 0.12), 0.001, 0.03), 0)
    place(out, 0.18 * bell(1760 * (1 + 0.05 * variant), 0.35, 0.1, bright=0.4), 0.01)
    return fade_out(normalize(out, -1.5))


def crit(variant):
    sec = 0.6
    out = np.zeros(int(SR * sec))
    t = t_axis(0.2)
    place(out, 0.8 * np.sin(2 * np.pi * (180 - 300 * t) * t) * env(len(t), 0.002, 0.04), 0)
    place(out, 0.5 * noise_band(0.08, 1500, 7000) * env(int(SR * 0.08), 0.001, 0.02), 0)
    base = 1975.5 * (1 + 0.04 * variant)
    place(out, 0.45 * bell(base, 0.5, 0.18), 0.01)
    place(out, 0.35 * bell(base * 1.5, 0.45, 0.15), 0.05)
    place(out, sparkles(0.5, 10, 5000, 10000, 0.3), 0.02)
    return fade_out(normalize(out, -1.5))


EQUIP = {  # base note, brightness, how much "clink" vs "rustle"
    'generic': (880, 0.5, 0.5), 'leather': (660, 0.2, 0.15), 'chain': (1320, 0.8, 0.9),
    'iron': (1046.5, 0.7, 0.7), 'gold': (1318.5, 1.0, 0.7), 'diamond': (1567.98, 1.0, 0.8),
    'netherite': (523.25, 0.6, 0.8),
}


def equip(kind, variant):
    f, bright, clink = EQUIP[kind]
    f *= (1 + 0.03 * variant)
    sec = 0.7
    out = np.zeros(int(SR * sec))
    place(out, (1 - clink) * 0.6 * noise_band(0.25, 500, 3000) * np.sin(np.pi * np.linspace(0, 1, int(SR * 0.25))), 0)
    for i, ratio in enumerate((1.0, 1.25, 1.5, 2.0)):  # a quick little jingle up
        place(out, clink * 0.5 * bell(f * ratio, 0.55, 0.16, bright), 0.03 + i * 0.045)
    if kind == 'chain':
        place(out, sparkles(0.4, 14, 4000, 9000, 0.25), 0.02)
    return fade_out(normalize(out, -3.0))


def write_ogg(path, x):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as f:
        wav = f.name
    wavfile.write(wav, SR, (np.clip(x, -1, 1) * 32767).astype(np.int16))
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', wav, '-ac', '1', '-c:a', 'libvorbis', '-q:a', '5', path], check=True)
    os.unlink(wav)


def main(out):
    write_ogg(os.path.join(out, 'item/totem_use.ogg'), totem())
    for v in range(3):
        write_ogg(os.path.join(out, f'weapon/sweep{v + 1}.ogg'), sweep(v))
        write_ogg(os.path.join(out, f'weapon/strong{v + 1}.ogg'), strong(v))
        write_ogg(os.path.join(out, f'weapon/crit{v + 1}.ogg'), crit(v))
    for kind in EQUIP:
        for v in range(2):
            write_ogg(os.path.join(out, f'armor/equip_{kind}{v + 1}.ogg'), equip(kind, v))


if __name__ == '__main__':
    main(sys.argv[1])
