#!/usr/bin/env python3
"""
Real-signal audio features for one track: tempo, RMS energy, spectral centroid.

Invoked once per track by AudioFeatureService (one process per file, like ffprobe is
already invoked elsewhere in this app) rather than embedding a DSP library in the JVM —
aubio is purpose-built for tempo/onset/spectral analysis and validated against this
library's real tracks; a from-scratch Java implementation would not be.

Usage: audio_features.py <path> <analyze_seconds>
Output: one line of JSON: {"bpm": float|null, "energy_rms": float|null,
                           "spectral_centroid": float|null, "error": string|null}
A decode failure is reported via "error", not a non-zero exit — the caller treats a
missing feature set as "leave mood/activity unset", not as a crash.
"""
import json
import sys

SR = 22050
HOP = 512
WIN = 1024


def analyze(path, analyze_seconds):
    import numpy as np
    import aubio

    src = aubio.source(path, SR, HOP)
    samplerate = src.samplerate
    tempo_o = aubio.tempo("default", WIN, HOP, samplerate)
    pv = aubio.pvoc(WIN, HOP)
    specdesc = aubio.specdesc("centroid", WIN)

    max_frames = analyze_seconds * samplerate
    frames_read = 0
    beat_times = []
    rms_vals = []
    centroid_vals = []

    while frames_read < max_frames:
        samples, read = src()
        if read == 0:
            break
        rms_vals.append(float(np.sqrt(np.mean(samples[:read] ** 2))))
        spec = pv(samples)
        centroid_vals.append(float(specdesc(spec)[0]))
        if tempo_o(samples):
            beat_times.append(tempo_o.get_last_s())
        frames_read += read
        if read < HOP:
            break

    bpm = float(tempo_o.get_bpm()) if hasattr(tempo_o, "get_bpm") else 0.0
    if bpm <= 0 and len(beat_times) >= 2:
        intervals = np.diff(beat_times)
        intervals = intervals[intervals > 0.2]
        if len(intervals):
            bpm = 60.0 / float(np.median(intervals))

    if not rms_vals:
        raise ValueError("no samples decoded")

    return {
        "bpm": round(bpm, 1) if bpm > 0 else None,
        "energy_rms": round(float(np.mean(rms_vals)), 5),
        "spectral_centroid": round(float(np.mean(centroid_vals)), 2) if centroid_vals else None,
        "error": None,
    }


def main():
    if len(sys.argv) < 2:
        print(json.dumps({"bpm": None, "energy_rms": None, "spectral_centroid": None,
                           "error": "usage: audio_features.py <path> [analyze_seconds]"}))
        sys.exit(0)
    path = sys.argv[1]
    analyze_seconds = int(sys.argv[2]) if len(sys.argv) > 2 else 60
    try:
        result = analyze(path, analyze_seconds)
    except Exception as e:
        result = {"bpm": None, "energy_rms": None, "spectral_centroid": None, "error": str(e)}
    print(json.dumps(result))


if __name__ == "__main__":
    main()
