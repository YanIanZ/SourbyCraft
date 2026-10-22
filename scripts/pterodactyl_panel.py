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
* The client API is rate limited (240 requests a minute by default) and a burst ends in a
  dropped TLS connection or a 429, so calls are retried with backoff and bulk senders pace.
"""
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request

ANSI = re.compile(r"\x1b\[[0-9;]*m")
_base = ""


def configure(panel_url, server_id):
    global _base
    if "PTERODACTYL_KEY" not in os.environ:
        raise SystemExit("set PTERODACTYL_KEY to a client API key; it is never read from a file")
    _base = f"{panel_url.rstrip('/')}/api/client/servers/{server_id}"


RETRIES = 5
# Minimum spacing for bulk console commands: 240 requests a minute, with headroom for the
# log reads that go alongside them.
COMMAND_SPACING = 0.35


def call(method, path, body=None, raw=False, data=None):
    """One API request, retried on rate limiting and dropped connections, never on 4xx errors."""
    for attempt in range(RETRIES):
        try:
            return _call(method, path, body, raw, data)
        except urllib.error.HTTPError as error:
            if error.code != 429 and error.code < 500 or attempt == RETRIES - 1:
                raise
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            if attempt == RETRIES - 1:
                raise
        time.sleep(2 ** attempt)


def _call(method, path, body, raw, data):
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


def list_dir(directory):
    """Names in a panel directory."""
    data = call("GET", "/files/list?directory=" + urllib.parse.quote(directory))
    return [item["attributes"]["name"] for item in data["data"]]


def create_folder(parent, name):
    call("POST", "/files/create-folder", {"root": parent, "name": name})


def upload(local_path, directory):
    """Upload a local (binary) file into a panel directory, through the signed upload URL."""
    url = call("GET", "/files/upload")["attributes"]["url"]
    name = os.path.basename(local_path)
    with open(local_path, "rb") as handle:
        content = handle.read()
    boundary = "----sourbycraft" + str(int(time.time() * 1000))
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"files\"; filename=\"{name}\"\r\n"
            f"Content-Type: application/octet-stream\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
    target = url + ("&" if "?" in url else "?") + "directory=" + urllib.parse.quote(directory)
    for attempt in range(RETRIES):
        request = urllib.request.Request(target, method="POST", data=body, headers={
            "Content-Type": "multipart/form-data; boundary=" + boundary,
            "User-Agent": "sourbycraft-panel-tools/1.0"})
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                response.read()
            return
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            if attempt == RETRIES - 1:
                raise
            time.sleep(2 ** attempt)
            url = call("GET", "/files/upload")["attributes"]["url"]
            target = url + ("&" if "?" in url else "?") + "directory=" + urllib.parse.quote(directory)


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
    """Start and wait for running. A start sent while the node is still settling after a stop
    is sometimes ignored (no process, no log); it is sent again while the server stays offline."""
    deadline = time.monotonic() + timeout
    power("start")
    resend = time.monotonic() + 90
    while time.monotonic() < deadline:
        current = state()
        if current == "running":
            return True
        if current == "offline" and time.monotonic() >= resend:
            power("start")
            resend = time.monotonic() + 90
        time.sleep(3)
    return False
