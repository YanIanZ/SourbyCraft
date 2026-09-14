#!/usr/bin/env python3
"""Discover a server's login/configuration packet identifiers by talking to it.

Packet ids move between Minecraft versions, so a client written from memory is a guess.
This connects, drives the login handshake, and reports what actually came back: the
protocol version the server advertises, then every frame's packet id and length through
login and configuration. Its output is the input to writing a real client.
"""
import argparse
import json
import socket
import sys
import time
import zlib

sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parent))
from baseline_network import read_varint, write_varint


def frame(packet_id, payload=b"", threshold=-1):
    body = write_varint(packet_id) + payload
    if threshold < 0:
        return write_varint(len(body)) + body
    if len(body) < threshold:
        body = write_varint(0) + body                  # Uncompressed, marked by a zero length.
        return write_varint(len(body)) + body
    compressed = write_varint(len(body)) + zlib.compress(body)
    return write_varint(len(compressed)) + compressed


def read_frame(stream, threshold=-1):
    length = read_varint(stream)
    data = stream.read(length)
    if len(data) != length:
        raise ConnectionError("short read")
    if threshold >= 0:
        view = _Reader(data)
        uncompressed = read_varint(view)
        data = zlib.decompress(view.rest()) if uncompressed else view.rest()
    view = _Reader(data)
    return read_varint(view), view.rest()


class _Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def read(self, count):
        chunk = self.data[self.pos:self.pos + count]
        self.pos += len(chunk)
        return chunk

    def rest(self):
        return self.data[self.pos:]


def status(host, port):
    """Server list ping: the cheapest way to learn the advertised protocol version."""
    address = host.encode()
    with socket.create_connection((host, port), timeout=5) as connection:
        connection.settimeout(10)
        connection.sendall(frame(0x00, write_varint(-1) + write_varint(len(address))
                                 + address + port.to_bytes(2, "big") + write_varint(1))
                           + frame(0x00))
        with connection.makefile("rb") as stream:
            _, payload = read_frame(stream)
            view = _Reader(payload)
            text = view.read(read_varint(view)).decode("utf-8", "replace")
    return json.loads(text)


def login(host, port, protocol, name, seconds, login_ack=0x03,
          finish_config_clientbound=0x03, finish_config_serverbound=0x03):
    """Drive login and report every frame seen, so the real packet ids are observed."""
    address = host.encode()
    encoded = name.encode()
    seen = []
    threshold = -1
    stage = "login"
    with socket.create_connection((host, port), timeout=5) as connection:
        connection.settimeout(seconds)
        connection.sendall(frame(0x00, write_varint(protocol) + write_varint(len(address))
                                 + address + port.to_bytes(2, "big") + write_varint(2)))
        # Login Start: name, then the offline-mode UUID.
        uuid = b"\x00" * 16
        connection.sendall(frame(0x00, write_varint(len(encoded)) + encoded + uuid, threshold))
        deadline = time.monotonic() + seconds
        with connection.makefile("rb") as stream:
            while time.monotonic() < deadline:
                try:
                    packet_id, payload = read_frame(stream, threshold)
                except Exception as stop:
                    seen.append({"event": f"stream ended: {type(stop).__name__}: {stop}"})
                    break
                entry = {"stage": stage, "id": hex(packet_id), "bytes": len(payload),
                         "head": payload[:40].hex()}
                seen.append(entry)
                # Set Compression switches the framing for everything after it.
                if stage == "login" and threshold < 0 and len(payload) <= 3:
                    value = read_varint(_Reader(payload))
                    if 0 <= value < 1 << 24:
                        threshold = value
                        entry["note"] = f"Set Compression, threshold={value}"
                        continue
                if stage == "login" and len(payload) > 16:
                    # Login Success: acknowledge it to enter the configuration stage.
                    entry["note"] = "Login Success -> sending Login Acknowledged"
                    connection.sendall(frame(login_ack, b"", threshold))
                    # Field order from net.minecraft.server.level.ClientInformation#write:
                    # utf(language), byte(viewDistance), enum(chatVisibility), bool(chatColors),
                    # byte(modelCustomisation), enum(mainHand), bool(textFiltering),
                    # bool(allowsListing), enum(particleStatus). Enums are VarInt ordinals.
                    locale = b"en_us"
                    client_info = (write_varint(len(locale)) + locale
                                   + bytes([10])                 # view distance
                                   + write_varint(0)             # chat visibility: FULL
                                   + bytes([1])                  # chat colours
                                   + bytes([0x7F])               # model customisation
                                   + write_varint(1)             # main hand: RIGHT
                                   + bytes([0])                  # text filtering
                                   + bytes([1])                  # allows listing
                                   + write_varint(0))            # particle status: ALL
                    connection.sendall(frame(0x00, client_info, threshold))
                    stage = "configuration"
                    continue
                if stage == "configuration" and packet_id == 0x04:
                    # Keep Alive: echo the payload back or the server disconnects us.
                    connection.sendall(frame(0x04, payload, threshold))
                    entry["note"] = "Keep Alive -> echoed"
                    continue
                if stage == "configuration" and packet_id == 0x0E:
                    # Known Packs: replying with an empty list asks for full registry data.
                    connection.sendall(frame(0x07, write_varint(0), threshold))
                    entry["note"] = "Known Packs -> replied empty"
                    continue
                if stage == "configuration" and packet_id == finish_config_clientbound:
                    entry["note"] = "Finish Configuration -> acknowledging, entering play"
                    connection.sendall(frame(finish_config_serverbound, b"", threshold))
                    stage = "play"
                    continue
    return seen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25705)
    parser.add_argument("--name", default="BaselineProbe")
    parser.add_argument("--seconds", type=float, default=6.0)
    parser.add_argument("--login-ack", type=lambda v: int(v, 0), default=0x03)
    parser.add_argument("--finish-in", type=lambda v: int(v, 0), default=0x03)
    parser.add_argument("--finish-out", type=lambda v: int(v, 0), default=0x03)
    args = parser.parse_args()

    info = status(args.host, args.port)
    version = info.get("version", {})
    print(f"server: {version.get('name')!r} protocol {version.get('protocol')}")
    print(f"players: {info.get('players', {}).get('online')} / {info.get('players', {}).get('max')}")
    print()
    for entry in login(args.host, args.port, version.get("protocol", -1), args.name, args.seconds,
                       args.login_ack, args.finish_in, args.finish_out):
        print("  " + json.dumps(entry))


if __name__ == "__main__":
    main()
