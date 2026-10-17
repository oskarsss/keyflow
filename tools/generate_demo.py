#!/usr/bin/env python3
"""Reproducible, original synthesized piano sketches; no downloaded demo music."""
import json
import math
from pathlib import Path
import struct
import wave

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/assets/demo"
TRACKS = [
    ("first-light", "First Light", "A gentle beginning", 84, [60, 64, 67, 72, 67, 64, 62, 67]),
    ("violet-hour", "Violet Hour", "Find your flow", 104, [57, 60, 64, 69, 67, 64, 60, 64]),
    ("afterglow", "Afterglow", "A little more momentum", 124, [62, 66, 69, 74, 73, 69, 66, 69]),
]


def gesture_chart(notes: list[dict]) -> list[dict]:
    """Group a few neighboring notes into readable hold/slide phrases."""
    output, i = [], 0
    while i < len(notes):
        note = dict(notes[i])
        # Leave opening notes simple, then introduce an occasional sustained gesture.
        if i >= 10 and i % 12 == 10 and i + 2 < len(notes):
            note.update(kind="hold", duration=round(notes[i+2]["time"]-note["time"], 3), endLane=note["lane"])
            i += 3
        elif i >= 16 and i % 18 == 16 and i + 2 < len(notes):
            end_lane = notes[i+2]["lane"]
            if end_lane == note["lane"]:
                end_lane = (end_lane+1) % 4
            note.update(kind="slide", duration=round(notes[i+2]["time"]-note["time"], 3), endLane=end_lane)
            i += 3
        else:
            note["kind"] = "tap"
            i += 1
        output.append(note)
    return output


def generate():
    ROOT.mkdir(parents=True, exist_ok=True)
    songs = []
    rate = 22050
    for sid, title, subtitle, bpm, melody in TRACKS:
        beat = 60/bpm
        times = [1.5+i*beat for i in range(48)]
        duration = times[-1]+2
        samples = [0.] * int(duration*rate)
        for i, start in enumerate(times):
            frequency = 440*2**((melody[i % len(melody)]-69)/12)
            for j in range(int(min(beat*1.6, 1.2)*rate)):
                t = j/rate
                envelope = min(1, t/.008)*math.exp(-t*4)
                tone = sum(math.sin(2*math.pi*frequency*k*t)*a for k, a in [(1,.6),(2,.22),(3,.1)])
                index = int(start*rate)+j
                if index < len(samples):
                    samples[index] += .4*envelope*tone
            if i % 4 == 0:
                for j in range(int(beat*2*rate)):
                    t = j/rate
                    index = int(start*rate)+j
                    if index < len(samples):
                        samples[index] += .15*math.sin(2*math.pi*frequency/2*t)*min(1,t/.02)*math.exp(-t*2)
        with wave.open(str(ROOT/(sid+".wav")), "wb") as audio:
            audio.setparams((1,2,rate,0,"NONE","not compressed"))
            audio.writeframes(b"".join(struct.pack("<h", int(max(-1,min(1,s))*24000)) for s in samples))
        chart = {"duration": duration, "method": "composed", "notes": [
            {"time": round(t,3), "lane": [0,2,1,3,2,0,3,1][i % 8]} for i,t in enumerate(times)]}
        if sid != "first-light":
            chart["notes"] = gesture_chart(chart["notes"])
        (ROOT/(sid+".json")).write_text(json.dumps(chart))
        songs.append({"id": sid, "title": title, "artist": "Keyflow Originals", "subtitle": subtitle,
                      "duration": duration, "bpm": bpm, "status": "ready", "demo": True})
    (ROOT/"playlist.json").write_text(json.dumps({"id":"keyflow-originals", "title":"The first notes", "songs":songs}))


if __name__ == "__main__":
    generate()
