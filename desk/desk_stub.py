#!/usr/bin/env python3
"""A minimal stand-in for the desktop side, so the phone half can be tested on its own.

The phone's listener (phone/src/Listen.java) POSTs what it heard here and acts on the answer:

  POST /phone   Authorization: Bearer <token>   Content-Type: application/json
  {"text": "how's the weather", "audio": true, "away": false}

  200 {"miss": false, "what": "<what ran>", "said": ["reply line", ...], "wav_b64": "<optional 16-bit PCM WAV>"}
  200 {"miss": false, ..., "pending": "<job id>"} -> a slow job: play the reply now ("On it."), then ask for the outcome
  200 {"miss": true,  "why": "<reason>"}          -> the phone opens its stock assistant
  200 {"miss": true,  "why": "nothing heard"}     -> the phone stays quiet (only the wake word was said)

  POST /phone/pending  {"id": "<job id>"}   held open until the job ends (the phone waits up to 90 s)
  200 {"said": ["the answer"], "wav_b64": "<WAV>"}  -> the phone speaks it; if the last line ends in "?", it opens
                                                      the mic again for your answer (at most 2 rounds)

  POST /phone/alerts   {"since": <last seq>}   long poll, used by phone/alertd.sh (optional)
  200 audio/wav, headers X-Seq: <seq>, X-Text: <the line>  -> the phone plays it unless it is docked
  204 (after a hold, X-Seq optional)                        -> nothing new; the phone polls again

"away" is true when the phone is off the home Wi-Fi, so the desktop can refuse things that only make sense
at home. In my setup this endpoint is my desktop voice assistant's own router, which hands anything it has no
command for to a background Claude Code job (the "pending" path). This stub only knows three phrases and never
sends audio (the phone then plays a short "done" chirp on a hit, and the "gave up" tone for a pending answer
without audio). Its /phone/alerts never has an alert: it just holds the poll and answers 204.

usage: VOICE_TOKEN=<secret> desk_stub.py [--port 7783] [--bind 0.0.0.0]
"""
import argparse
import hmac
import json
import os
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

WAKE = re.compile(r"^\s*(hey\s+)?computer[\s,.!?]*", re.I)
RULES = [
    (re.compile(r"\bwhat time is it\b", re.I), "clock", lambda m: ["It is time to test the phone."]),
    (re.compile(r"\b(turn|switch) (on|off) the (\w+) lights?\b", re.I), "lights",
     lambda m: [f"Turning {m.group(2)} the {m.group(3)} lights."]),
]
SLOW = re.compile(r"\bthink about it\b", re.I)    # demo of the "pending" path: answers 3 s later
ALERT_HOLD_S = 30


def route(text, away):
    text = WAKE.sub("", text).strip()
    if not text:
        return {"miss": True, "why": "nothing heard", "text": ""}
    if SLOW.search(text):
        return {"miss": False, "what": "slow", "text": text, "said": ["On it."], "pending": "job1"}
    for rx, what, reply in RULES:
        m = rx.search(text)
        if m:
            return {"miss": False, "what": what, "text": text, "said": reply(m)}
    return {"miss": True, "why": "no match", "text": text}


def make_handler(token):
    class H(BaseHTTPRequestHandler):
        def log_message(self, fmt, *a):
            sys.stderr.write("desk_stub: " + fmt % a + "\n")

        def reply(self, obj, code=200):
            body = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            good = hmac.compare_digest(self.headers.get("Authorization", "").encode(), ("Bearer " + token).encode())
            if not good:
                return self.reply({"error": "forbidden"}, 403)
            if self.path not in ("/phone", "/phone/pending", "/phone/alerts"):
                return self.reply({"error": "no"}, 404)
            n = int(self.headers.get("Content-Length") or 0)
            if not 0 < n <= 1 << 16:
                return self.reply({"error": "bad length"}, 400)
            try:
                req = json.loads(self.rfile.read(n))
            except ValueError:
                return self.reply({"error": "bad json"}, 400)
            if self.path == "/phone/pending":
                time.sleep(3)
                return self.reply({"said": ["Done thinking."]})
            if self.path == "/phone/alerts":
                time.sleep(ALERT_HOLD_S)
                self.send_response(204)
                self.end_headers()
                return
            self.reply(route(str(req.get("text") or ""), bool(req.get("away"))))
    return H


def main():
    ap = argparse.ArgumentParser(description="stand-in desktop endpoint for the phone listener")
    ap.add_argument("--port", type=int, default=7783)
    ap.add_argument("--bind", default="0.0.0.0")
    a = ap.parse_args()
    token = os.environ.get("VOICE_TOKEN", "")
    if len(token) < 16:
        sys.exit("set VOICE_TOKEN to a random string of 16+ characters (the phone keeps the same one)")
    ThreadingHTTPServer((a.bind, a.port), make_handler(token)).serve_forever()


if __name__ == "__main__":
    main()
