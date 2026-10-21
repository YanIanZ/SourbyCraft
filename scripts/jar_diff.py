#!/usr/bin/env python3
"""Explain why two jars that should be byte-identical are not (the CI `rebuild` job calls this).

Compares two ZIP/JAR files entry by entry and descends into nested jars: entry order, and per entry the
CRC-32, sizes, compression method, time stamp, extra field, comment and attributes. Small text entries
that differ (build properties, manifests, version lists) are printed as a unified diff, which is how a
differing treeHash, buildTimestamp or gitBranch shows up. Exit 0 when every compared field is equal,
1 when anything differs, 2 on a usage error. It never normalizes anything away.

    python3 scripts/jar_diff.py first.jar second.jar
"""
import argparse
import difflib
import io
import sys
import zipfile

MAX_TEXT = 64 * 1024
HEADER_FIELDS = ("CRC", "file_size", "compress_size", "compress_type", "date_time", "extra", "comment",
                 "create_system", "external_attr", "flag_bits")


def header(info):
    return {field: getattr(info, field) for field in HEADER_FIELDS}


def text_lines(data):
    if len(data) > MAX_TEXT:
        return None
    try:
        return data.decode("utf-8").splitlines()
    except UnicodeDecodeError:
        return None


def compare(left, right, label, out, depth=0):
    """Append difference lines to out; return the number of differing facts."""
    differences = 0
    with zipfile.ZipFile(left) as a, zipfile.ZipFile(right) as b:
        names_a, names_b = a.namelist(), b.namelist()
        if names_a != names_b:
            if sorted(names_a) == sorted(names_b):
                out.append(f"{label}: same entries, different ORDER")
                differences += 1
            for name in sorted(set(names_a) - set(names_b)):
                out.append(f"{label}: only in first: {name}")
                differences += 1
            for name in sorted(set(names_b) - set(names_a)):
                out.append(f"{label}: only in second: {name}")
                differences += 1
        if a.comment != b.comment:
            out.append(f"{label}: archive comment differs")
            differences += 1
        for name in names_a:
            if name not in set(names_b):
                continue
            info_a, info_b = a.getinfo(name), b.getinfo(name)
            ha, hb = header(info_a), header(info_b)
            fields = [f for f in HEADER_FIELDS if ha[f] != hb[f]]
            if not fields:
                continue
            differences += 1
            out.append(f"{label}!{name}: " + "; ".join(f"{f}: {ha[f]!r} != {hb[f]!r}" for f in fields))
            if info_a.is_dir() or "CRC" not in fields and "file_size" not in fields:
                continue
            data_a, data_b = a.read(name), b.read(name)
            if name.endswith(".jar") and depth < 3:
                try:
                    differences += compare(io.BytesIO(data_a), io.BytesIO(data_b), f"{label}!{name}", out, depth + 1)
                    continue
                except zipfile.BadZipFile:
                    pass
            lines_a, lines_b = text_lines(data_a), text_lines(data_b)
            if lines_a is not None and lines_b is not None:
                out.extend(difflib.unified_diff(lines_a, lines_b, f"first:{name}", f"second:{name}", lineterm=""))
            else:
                out.append(f"{label}!{name}: binary content differs ({len(data_a)} vs {len(data_b)} bytes)")
    return differences


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("first")
    parser.add_argument("second")
    args = parser.parse_args(argv)
    out = []
    try:
        count = compare(args.first, args.second, "jar", out)
    except (OSError, zipfile.BadZipFile) as error:
        print(f"jar_diff: {error}", file=sys.stderr)
        return 2
    print("\n".join(out))
    print(f"jar_diff: {count} differing fact(s)" if count else "jar_diff: every compared field is equal")
    return 1 if count else 0


if __name__ == "__main__":
    sys.exit(main())
