#!/usr/bin/env python3
"""Drive a Pterodactyl-hosted server through the panel's client API.

Shared by the panel-side tools (verify_persistence_panel.py, bench_panel.py). The API key
comes from PTERODACTYL_KEY and is never read from or written to a file.

Things learned against a real panel, kept here so every tool gets them right:
* Cloudflare in front of the panel returns 403 to Python's default User-Agent.
* After a start, logs/latest.log still holds the previous boot's "Done (" until the new
  process rotates it; the panel's own "running" state is the reliable readiness signal.
* The console colours its output, which splits numbers from their suffixes; text read back
  is returned with the colour codes removed.
"""
import json
import os
import re
import time
import urllib.parse
import urllib.request

ANSI = re.compile(r"\x1b\[[0-9;]*m")
_base = ""


def configure(panel_url, server_id):
    global _base
    if "PTERODACTYL_KEY" not in os.environ:
        raise SystemExit("set PTERODACTYL_KEY to a client API key; it is never read from a file")
    _base = f"{panel_url.rstrip('/')}/api/client/servers/{server_id}"


def call(method, path, body=None, raw=False, data=None):
    request = urllib.request.Request(_base + path, method=method, headers={
        "Authorization": "Bearer " + os.environ["PTERODACTYL_KEY"],
        "Accept": "application/json",
        "Content-Type": "text/plain" if data is not None else "application/json",
        "User-Agent": "sourbycraft-panel-tools/1.0"})
    payload = data if data is not None else (json.dumps(body).encode() if body is not None else None)
    with urllib.request.urlopen(request, payload, timeout=60) as response:
        result = response.read()
    if raw:
        return result.decode("utf-8", "replace")
    return json.loads(result) if result else None


def state():
    return call("GET", "/resources")["attributes"]["current_state"]


def power(signal):
    call("POST", "/power", {"signal": signal})


def command(text):
    call("POST", "/command", {"command": text})


def read_file(path):
    return call("GET", "/files/contents?file=" + urllib.parse.quote(path), raw=True)


def write_file(path, text):
    call("POST", "/files/write?file=" + urllib.parse.quote(path), data=text.encode())


def rename(source, target):
    call("PUT", "/files/rename", {"root": "/", "files": [{"from": source, "to": target}]})


def log():
    return ANSI.sub("", read_file("/logs/latest.log"))


def wait_state(wanted, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if state() == wanted:
            return True
        time.sleep(3)
    return False


def wait_log(pattern, timeout, after=0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        text = log()
        match = re.search(pattern, text[after:])
        if match:
            return text, match
        time.sleep(3)
    return log(), None


def run(text, expect, timeout=40):
    """Send a console command and return the log text it produced, read back from latest.log."""
    before = len(log())
    command(text)
    _, match = wait_log(expect, timeout, before)
    return log()[before:], match


def stop(timeout=600):
    power("stop")
    return wait_state("offline", timeout)


def start(timeout=900):
    power("start")
    return wait_state("running", timeout)
