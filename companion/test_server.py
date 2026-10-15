import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

from server import Library, Handler, playlist_url, chart_from_audio, gesture_chart


class PlaylistTests(unittest.TestCase):
    def test_links_are_canonicalized_and_arbitrary_hosts_rejected(self):
        self.assertEqual(playlist_url("https://www.youtube.com/watch?v=abcd&list=PLA2wtd_DK6uw"),
                         "https://music.youtube.com/playlist?list=PLA2wtd_DK6uw")
        for value in ["file:///etc/passwd", "http://music.youtube.com/playlist?list=PLA2wtd_DK6uw",
                      "https://music.youtube.com.evil.test/playlist?list=PLA2wtd_DK6uw",
                      "https://music.youtube.com/playlist?list=x", "https://youtube.com/channel/foo?list=PLA2wtd_DK6uw"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                playlist_url(value)

    def test_import_shuffles_once_and_prefetch_uses_local_order(self):
        with tempfile.TemporaryDirectory() as temp:
            library=Library(Path(temp),"test",3)
            entries=[{"id": f"songid{i:05d}", "title": f"Song {i}", "uploader": "Artist"} for i in range(5)]
            result=type("Result",(),{"returncode":0,"stdout":json.dumps({"title":"My mix","entries":entries}),"stderr":""})()
            cached=[]
            def cache(playlist,song):
                cached.append(song["id"]); song["status"]="ready"
                directory = Path(temp) / song["id"]
                directory.mkdir(exist_ok=True)
                (directory / "audio.mp3").write_bytes(b"audio")
                (directory / "chart.json").write_text("{}")
            job={}
            with patch("server.subprocess.run",return_value=result), patch("server.random.shuffle", side_effect=lambda songs: songs.reverse()), patch.object(library,"cache_song",side_effect=cache):
                library.import_playlist("https://music.youtube.com/playlist?list=PLA2wtd_DK6uw",job)
            self.assertEqual(job["status"],"complete")
            self.assertEqual(cached,[e["id"] for e in entries[::-1][:3]])
            self.assertEqual([s["title"] for s in job["playlist"]["songs"]],[f"Song {i}" for i in reversed(range(5))])
            self.assertNotIn("url", job["playlist"])
            original = library.snapshot(job["playlist"])
            library.request_prefetch(original["id"], 1)
            library.executor.submit(lambda: None).result(timeout=5)
            second = {}
            with patch("server.subprocess.run", return_value=result):
                library.import_playlist("https://music.youtube.com/playlist?list=PLA2wtd_DK6uw", second, 0)
            self.assertNotEqual(original["id"], second["playlist"]["id"])
            self.assertEqual(len(library.playlists), 2)
            self.assertEqual(library.playlists[original["id"]]["songs"], original["songs"])
            self.assertEqual(second["playlist"]["preloadCount"], 0)
            library.executor.shutdown()
            restored = Library(Path(temp), "test", 3)
            self.assertEqual(restored.playlists[original["id"]]["songs"], original["songs"])
            restored.executor.shutdown()

    def test_import_has_no_two_hundred_song_cap(self):
        with tempfile.TemporaryDirectory() as temp:
            library = Library(Path(temp), "test", 0)
            try:
                entries = [{"id": f"songid{i:05d}"} for i in range(350)]
                result = type("Result", (), {"returncode": 0, "stderr": "", "stdout": json.dumps(
                    {"title": "Large mix", "entries": entries + [entries[0], None]})})()
                job = {}
                with patch("server.subprocess.run", return_value=result) as run:
                    library.import_playlist("https://music.youtube.com/playlist?list=PLA2wtd_DK6uw", job)
                self.assertNotIn("--playlist-end", run.call_args.args[0])
                self.assertEqual(job["status"], "complete")
                self.assertEqual({s["id"] for s in job["playlist"]["songs"]}, {e["id"] for e in entries})
            finally:
                library.executor.shutdown()

    def test_independent_import_reuses_complete_shared_cache_after_restart(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            sid = "songid00000"
            (directory / sid).mkdir()
            (directory / sid / "audio.mp3").write_bytes(b"audio")
            (directory / sid / "chart.json").write_text("{}")
            original = {"id": "saved-mix", "title": "Saved mix",
                        "songs": [{"id": sid, "title": "Cached title", "artist": "Cached artist", "status": "ready"},
                                  {"id": "songid00001", "status": "caching"},
                                  {"id": "songid00002", "status": "ready"}]}
            (directory / "playlist-saved-mix.json").write_text(json.dumps(original))
            library = Library(directory, "test", 0)
            try:
                self.assertEqual([s["id"] for s in library.playlists["saved-mix"]["songs"]],
                                 [s["id"] for s in original["songs"]])
                self.assertEqual([s["status"] for s in library.playlists["saved-mix"]["songs"]],
                                 ["ready", "pending", "pending"])
                result = type("Result", (), {"returncode": 0, "stderr": "", "stdout": json.dumps(
                    {"title": "New import", "entries": [{"id": sid, "title": "Source title"}]})})()
                job = {}
                with patch("server.subprocess.run", return_value=result):
                    library.import_playlist("https://music.youtube.com/playlist?list=PLA2wtd_DK6uw", job)
                self.assertEqual(job["status"], "complete")
                self.assertNotEqual(job["playlist"]["id"], "saved-mix")
                self.assertEqual(job["playlist"]["songs"][0]["title"], "Cached title")
                self.assertEqual(job["playlist"]["songs"][0]["status"], "ready")
            finally:
                library.executor.shutdown()

    def test_real_audio_chart_is_deterministic_and_playable(self):
        audio=Path(__file__).resolve().parents[1]/"app/src/main/assets/demo/first-light.wav"
        chart=chart_from_audio(audio,"first-light")
        self.assertEqual(chart,chart_from_audio(audio,"first-light"))
        self.assertEqual(chart["method"],"energy-onsets")
        self.assertGreater(len(chart["notes"]),35)
        notes=chart["notes"]
        self.assertTrue(all(0<=n["lane"]<4 and 1.5<n["time"]<chart["duration"] for n in notes))
        self.assertTrue(all(b["time"]-a["time"]>=.299 for a,b in zip(notes,notes[1:])))

    def test_preload_count_is_per_playlist_and_persisted(self):
        with tempfile.TemporaryDirectory() as temp:
            library=Library(Path(temp), "test", 3)
            for identifier in ("mix-a", "mix-b"):
                library.playlists[identifier]={"id":identifier,"title":identifier,"preloadCount":3,
                    "songs":[{"id":f"songid{i:05d}","status":"pending"} for i in range(5)]}
            cached=[]
            def cache(playlist,song):
                cached.append(song["id"]); song["status"]="ready"
            with patch.object(library,"cache_song",side_effect=cache):
                library.request_prefetch("mix-a",2)
                library.executor.submit(lambda: None).result(timeout=5)
            self.assertEqual(cached,["songid00000","songid00001"])
            self.assertEqual(library.playlists["mix-b"]["preloadCount"],3)
            self.assertEqual(library.request_prefetch("mix-a",0)["preloadCount"],0)
            library.executor.shutdown()
            restored=Library(Path(temp),"test",3)
            self.assertEqual(restored.playlists["mix-a"]["preloadCount"],0)
            restored.executor.shutdown()
            for invalid in (-1,21,True,"3",None):
                with self.subTest(value=invalid), self.assertRaises(ValueError):
                    library.request_prefetch("mix-a",invalid)

    def test_gestures_follow_music_phrases_without_overlapping_the_next_note(self):
        notes=[{"time":round(1.5+i*.4,3),"lane":i%4} for i in range(60)]
        result=gesture_chart(notes)
        self.assertEqual({n["kind"] for n in result},{"tap","hold","slide"})
        self.assertTrue(all(n["kind"]=="tap" for n in result[:10]))
        for note,following in zip(result,result[1:]):
            self.assertLess(note["time"]+note.get("duration",0),following["time"])


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.library=Library(Path(self.temp.name),"test-token",0)
        handler=type("TestHandler",(Handler,),{"library":self.library})
        self.server=ThreadingHTTPServer(("127.0.0.1",0),handler)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True); self.thread.start()
        self.url=f"http://127.0.0.1:{self.server.server_port}"
        media=Path(self.temp.name)/"songid00000"; media.mkdir(); (media/"audio.mp3").write_bytes(b"0123456789")

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()
        self.library.executor.shutdown(); self.temp.cleanup()

    def request(self,path,headers=None,data=None):
        auth={"Authorization":"Bearer test-token"}; auth.update(headers or {})
        return urllib.request.urlopen(urllib.request.Request(self.url+path,headers=auth,data=data),timeout=5)

    def test_pairing_is_required(self):
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.request("/api/health",{"Authorization":"wrong"})
        self.assertEqual(error.exception.code,401)
        with self.request("/api/health") as response:
            self.assertTrue(json.load(response)["ok"])

    def test_audio_ranges_and_media_allowlist(self):
        with self.request("/media/songid00000/audio.mp3",{"Range":"bytes=2-5"}) as response:
            self.assertEqual(response.status,206); self.assertEqual(response.read(),b"2345")
            self.assertEqual(response.headers["Content-Range"],"bytes 2-5/10")
        for path,headers,expected in [
            ("/media/songid00000/audio.mp3",{"Range":"bytes=20-"},416),
            ("/media/songid00000/audio.mp3",{"Range":"bytes=8-3"},416),
            ("/media/../.pairing-token",{},404),
            ("/media/songid00000/download.log",{},404)]:
            with self.subTest(path=path), self.assertRaises(urllib.error.HTTPError) as error:
                self.request(path,headers)
            self.assertEqual(error.exception.code,expected)

    def test_bad_import_does_not_launch_a_downloader(self):
        with patch("server.subprocess.run") as process:
            with self.assertRaises(urllib.error.HTTPError) as error:
                self.request("/api/import",data=b'{"url":"https://example.com"}')
            self.assertEqual(error.exception.code,400); process.assert_not_called()


if __name__=="__main__":
    unittest.main()
