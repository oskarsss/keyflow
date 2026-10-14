#!/usr/bin/env python3
"""Keyflow's dependency-free, local playlist and audio companion."""
from __future__ import annotations

import argparse
import array
import copy
import hashlib
import hmac
import json
import math
import mimetypes
import os
from pathlib import Path
import re
import secrets
import shutil
import statistics
import subprocess
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

MAX_SONGS = 200
MAX_DURATION = 1200


def gesture_chart(notes: list[dict]) -> list[dict]:
    """Group a few neighboring onsets into readable hold/slide phrases."""
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


def playlist_url(value: str) -> str:
    parsed = urlparse(value.strip())
    if parsed.scheme != "https" or parsed.hostname not in {"music.youtube.com", "www.youtube.com", "youtube.com"}:
        raise ValueError("Paste an HTTPS YouTube Music or YouTube playlist link.")
    if parsed.path not in {"/playlist", "/watch"}:
        raise ValueError("This link must contain a playlist.")
    identifier = parse_qs(parsed.query).get("list", [""])[0]
    if not re.fullmatch(r"[A-Za-z0-9_-]{10,150}", identifier):
        raise ValueError("The link is missing a valid playlist ID.")
    return "https://music.youtube.com/playlist?list=" + identifier


def chart_from_audio(audio: Path, identifier: str) -> dict:
    """Energy-onset chart; times share the decoded audio's playback clock."""
    pcm = subprocess.run(["ffmpeg", "-v", "error", "-i", str(audio), "-t", str(MAX_DURATION),
                          "-f", "s16le", "-ac", "1", "-ar", "11025", "pipe:1"],
                         capture_output=True, check=True, timeout=120).stdout
    samples = array.array("h")
    samples.frombytes(pcm)
    if sys.byteorder != "little":
        samples.byteswap()
    duration = len(samples) / 11025
    if duration < 3:
        raise ValueError("This audio is too short to create a level.")
    hop = 256
    energy = [math.sqrt(sum(x*x for x in samples[i:i+hop]) / hop)
              for i in range(0, len(samples)-hop, hop)]
    flux = [max(0, energy[i]-energy[i-1]) for i in range(1, len(energy))]
    times = []
    for i in range(3, len(flux)-3):
        time = (i+1)*hop/11025
        neighborhood = flux[max(0, i-22):i+23]
        threshold = max(100, statistics.mean(neighborhood)*1.55)
        if 1.5 < time < duration-.7 and flux[i] > threshold and flux[i] == max(flux[i-3:i+4]):
            if not times or time-times[-1] >= .30:
                times.append(round(time, 3))
    method = "energy-onsets"
    if len(times) < duration/5:
        # Quiet/ambient audio still gets an explicitly labelled estimated chart.
        times = [round(1.5+i*.6, 3) for i in range(max(1, int((duration-2.2)/.6)))]
        method = "estimated-grid"
    seed = int(hashlib.sha256(identifier.encode()).hexdigest()[:8], 16)
    notes, previous = [], -1
    for i, time in enumerate(times):
        lane = (seed >> (i % 23) ^ i*7) % 4
        if lane == previous:
            lane = (lane+1) % 4
        notes.append({"time": time, "lane": lane})
        previous = lane
    return {"duration": round(duration, 3), "method": method, "version": 2, "notes": gesture_chart(notes)}


class Library:
    def __init__(self, directory: Path, token: str, prefetch: int = 3):
        self.directory = directory.resolve()
        self.directory.mkdir(parents=True, exist_ok=True)
        self.token, self.prefetch = token, prefetch
        self.lock = threading.RLock()
        self.jobs, self.playlists = {}, {}
        self.executor = ThreadPoolExecutor(max_workers=1)
        for path in self.directory.glob("playlist-*.json"):
            try:
                item = json.loads(path.read_text())
                for song in item["songs"]:
                    if song["status"] == "caching":
                        song["status"] = "pending"
                self.playlists[item["id"]] = item
            except (ValueError, KeyError):
                pass

    def snapshot(self, value):
        with self.lock:
            return copy.deepcopy(value)

    def save(self, playlist):
        with self.lock:
            path = self.directory / ("playlist-" + playlist["id"] + ".json")
            temporary = path.with_suffix(".tmp")
            temporary.write_text(json.dumps(playlist))
            temporary.replace(path)

    def start_import(self, url, prefetch=None):
        url = playlist_url(url)
        if prefetch is not None and (type(prefetch) is not int or not 0 <= prefetch <= 20):
            raise ValueError("Preload count must be between 0 and 20.")
        job_id = secrets.token_hex(12)
        job = {"id": job_id, "status": "importing", "message": "Reading your playlist…", "playlist": None}
        with self.lock:
            self.jobs[job_id] = job
        self.executor.submit(self.import_playlist, url, job, prefetch)
        return {"jobId": job_id}

    def import_playlist(self, url, job, prefetch=None):
        try:
            result = subprocess.run(["yt-dlp", "--ignore-config", "--flat-playlist", "--dump-single-json",
                                     "--yes-playlist", "--playlist-end", str(MAX_SONGS),
                                     "--socket-timeout", "20", "--", url],
                                    capture_output=True, text=True, timeout=180)
            (self.directory/("import-"+hashlib.sha256(url.encode()).hexdigest()[:20]+".log")).write_text(result.stderr[-100000:])
            if result.returncode != 0:
                raise ValueError("Playlist unavailable. Use a public or unlisted playlist; check the server's yt-dlp installation.")
            raw = json.loads(result.stdout)
            identifier = hashlib.sha256(url.encode()).hexdigest()[:20]
            old = self.playlists.get(identifier, {})
            existing = {s["id"]: s for s in old.get("songs", [])}
            songs = []
            for entry in raw.get("entries", []):
                if not entry or not re.fullmatch(r"[A-Za-z0-9_-]{11}", str(entry.get("id", ""))):
                    continue
                sid = entry["id"]
                if any(s["id"] == sid for s in songs):
                    continue
                thumbs = entry.get("thumbnails") or []
                song = {"id": sid, "title": entry.get("title") or "Untitled song",
                        "artist": entry.get("artist") or entry.get("uploader") or entry.get("channel") or "YouTube Music",
                        "duration": entry.get("duration") or 0,
                        "thumbnail": thumbs[-1].get("url", "") if thumbs else "",
                        "status": "pending"}
                cached = existing.get(sid)
                if cached and cached.get("status") == "ready" and (self.directory/sid/"audio.mp3").exists():
                    song.update(cached)
                songs.append(song)
            if not songs:
                raise ValueError("No playable songs found in this playlist.")
            count = prefetch if prefetch is not None else old.get("preloadCount", self.prefetch)
            playlist = {"id": identifier, "title": raw.get("title") or "My playlist", "url": url, "songs": songs, "preloadCount": count}
            with self.lock:
                self.playlists[identifier] = playlist
                job.update(status="caching", message="Preparing your first songs…", playlist=playlist)
            self.save(playlist)
            for song in songs[:count]:
                self.cache_song(playlist, song)
            with self.lock:
                job.update(status="complete", message="Playlist ready")
        except (ValueError, OSError, subprocess.SubprocessError) as error:
            with self.lock:
                job.update(status="error", message=str(error))

    def request_cache(self, playlist_id, song_id):
        with self.lock:
            playlist = self.playlists.get(playlist_id)
            if not playlist:
                raise ValueError("Playlist not found. Import it again.")
            song = next((s for s in playlist["songs"] if s["id"] == song_id), None)
            if not song:
                raise ValueError("Song not found.")
            if song["status"] not in {"ready", "caching"}:
                song.update(status="caching", error="")
                self.executor.submit(self.cache_song, playlist, song)
            return self.snapshot(song)

    def request_prefetch(self, playlist_id, count):
        if type(count) is not int or not 0 <= count <= 20:
            raise ValueError("Preload count must be between 0 and 20.")
        with self.lock:
            playlist = self.playlists.get(playlist_id)
            if not playlist:
                raise ValueError("Playlist not found. Import it again.")
            playlist["preloadCount"] = count
            self.save(playlist)
            for song in playlist["songs"][:count]:
                self.request_cache(playlist_id, song["id"])
            return self.snapshot(playlist)

    def cache_song(self, playlist, song):
        if song["status"] == "ready":
            return
        sid = song["id"]
        directory = self.directory/sid
        directory.mkdir(exist_ok=True)
        with self.lock:
            song.update(status="caching", error="")
        self.save(playlist)
        try:
            cmd = ["yt-dlp", "--ignore-config", "--format", "bestaudio", "--extract-audio", "--audio-format", "mp3",
                   "--audio-quality", "0", "--yes-playlist", "--add-metadata", "--embed-thumbnail",
                   "--write-thumbnail", "--convert-thumbnails", "jpg", "--write-info-json", "--no-mtime",
                   "--verbose", "--no-progress", "--socket-timeout", "20", "--retries", "2",
                   "--max-filesize", "100M", "--match-filters", "duration <= 1200",
                   "--output", str(directory/"source.%(ext)s"), "--", "https://www.youtube.com/watch?v="+sid]
            if shutil.which("node"):
                cmd[1:1] = ["--js-runtimes", "node"]
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
            # Keep yt-dlp diagnostics on the machine, never in API responses.
            (directory/"download.log").write_text(result.stderr[-100000:])
            audio = directory/"source.mp3"
            if result.returncode or not audio.exists():
                raise ValueError("Download failed or track exceeds 20 minutes. Update yt-dlp and inspect download.log, then retry.")
            audio.replace(directory/"audio.mp3")
            metadata = json.loads((directory/"source.info.json").read_text())
            chart = chart_from_audio(directory/"audio.mp3", sid)
            (directory/"chart.json").write_text(json.dumps(chart))
            thumbnail = directory/"source.jpg"
            if thumbnail.exists():
                thumbnail.replace(directory/"cover.jpg")
            with self.lock:
                song.update(status="ready", title=metadata.get("track") or metadata.get("title") or song["title"],
                            artist=metadata.get("artist") or metadata.get("uploader") or song["artist"],
                            duration=chart["duration"], audio=f"/media/{sid}/audio.mp3",
                            chart=f"/media/{sid}/chart.json", chartMethod=chart["method"])
                if (directory/"cover.jpg").exists():
                    song["cover"] = f"/media/{sid}/cover.jpg"
        except (ValueError, OSError, subprocess.SubprocessError) as error:
            with self.lock:
                song.update(status="error", error=str(error))
        self.save(playlist)


class Handler(BaseHTTPRequestHandler):
    library: Library

    def log_message(self, format, *args):
        # Avoid logging playlist URLs, tokens, and song titles.
        pass

    def json_response(self, data, status=200):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorized(self):
        expected = "Bearer " + self.library.token
        if not hmac.compare_digest(self.headers.get("Authorization", ""), expected):
            self.json_response({"error": "Pairing code is incorrect. Copy it from your companion server."}, 401)
            return False
        return True

    def do_GET(self):
        if not self.authorized():
            return
        path = urlparse(self.path).path
        if path == "/api/health":
            return self.json_response({"ok": True, "service": "Keyflow", "version": 1})
        if path.startswith("/api/jobs/"):
            with self.library.lock:
                job = self.library.snapshot(self.library.jobs.get(path.split("/")[-1]))
            return self.json_response(job if job else {"error": "Import not found"}, 200 if job else 404)
        if path.startswith("/api/playlists/"):
            with self.library.lock:
                playlist = self.library.snapshot(self.library.playlists.get(path.split("/")[-1]))
            return self.json_response(playlist if playlist else {"error": "Playlist not found"}, 200 if playlist else 404)
        match = re.fullmatch(r"/media/([A-Za-z0-9_-]{11})/(audio\.mp3|chart\.json|cover\.jpg)", path)
        if match:
            file = self.library.directory / match[1] / match[2]
            if file.is_file():
                return self.send_file(file)
        self.json_response({"error": "Not found"}, 404)

    def send_file(self, file):
        length = file.stat().st_size
        start, end, partial = 0, length-1, False
        range_header = self.headers.get("Range")
        if range_header:
            match = re.fullmatch(r"bytes=(\d+)-(\d*)", range_header)
            if not match or int(match[1]) >= length or (match[2] and int(match[2]) < int(match[1])):
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{length}")
                self.end_headers()
                return
            start = int(match[1])
            end = min(int(match[2]) if match[2] else end, end)
            partial = True
        self.send_response(206 if partial else 200)
        self.send_header("Content-Type", mimetypes.guess_type(file.name)[0] or "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end-start+1))
        if partial:
            self.send_header("Content-Range", f"bytes {start}-{end}/{length}")
        self.end_headers()
        with file.open("rb") as stream:
            stream.seek(start)
            remaining = end-start+1
            while remaining:
                chunk = stream.read(min(65536, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

    def do_POST(self):
        if not self.authorized():
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 8192:
                raise ValueError("Invalid request size")
            data = json.loads(self.rfile.read(length))
            if not isinstance(data, dict):
                raise ValueError("Expected a JSON object")
            if self.path == "/api/import":
                return self.json_response(self.library.start_import(str(data.get("url", "")), data.get("prefetch")), 202)
            if self.path == "/api/cache":
                return self.json_response(self.library.request_cache(str(data.get("playlistId", "")), str(data.get("songId", ""))), 202)
            if self.path == "/api/prefetch":
                return self.json_response(self.library.request_prefetch(str(data.get("playlistId", "")), data.get("count")), 202)
            self.json_response({"error": "Not found"}, 404)
        except (ValueError, TypeError) as error:
            self.json_response({"error": str(error)}, 400)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--cache-dir", type=Path, default=Path(__file__).parent/"cache")
    parser.add_argument("--prefetch", type=int, default=3, choices=range(0, 21))
    args = parser.parse_args()
    for executable in ("yt-dlp", "ffmpeg"):
        if not shutil.which(executable):
            parser.error(f"Install {executable} before starting the companion.")
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    token_file = args.cache_dir/".pairing-token"
    if not token_file.exists():
        token_file.write_text(secrets.token_urlsafe(24))
        token_file.chmod(0o600)
    Handler.library = Library(args.cache_dir, token_file.read_text().strip(), args.prefetch)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"Keyflow companion listening on {args.host}:{args.port}", flush=True)
    print(f"Pairing code: {Handler.library.token}", flush=True)
    print("Android emulator URL: http://10.0.2.2:8765 | Phone: use this computer's LAN IP.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        Handler.library.executor.shutdown(wait=False, cancel_futures=True)


if __name__ == "__main__":
    main()
