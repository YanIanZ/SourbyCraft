#!/usr/bin/env python3
"""Compile and run the real-peer AbyssLink mTLS interoperability harness.
The Leviathan checkout is read-only; temporary material is deleted. Exit 0 = 16/16
checks passed, 1 = failed or missing check, 2 = setup failure.
"""
import argparse, hashlib, json, os, re, shutil, subprocess, sys, tempfile
from pathlib import Path
SCHEMA = "sourbycraft.abysslink.interop.compact/v1"
HARNESS = Path(__file__).resolve().with_name("AbyssLinkInteropHarness.java")
PEER_DIR = "src/main/java/world/leviathan/abysslink"
SUPPORT = ("src/main/java/world/leviathan/license/Edition.java "
           "src/main/java/world/leviathan/cluster/ClusterConfig.java "
           "src/main/java/world/leviathan/netty/CidrMatcher.java").split()
REQUIRED = ("git-root sources-present classpath certificates compile certificate-material "
            "auth-hello heartbeat-observed ping-found ping-absent ping-stale reconnect "
            "main-cleanup negative-wrong-hostname negative-wrong-pin java-harness").split()
OPENSSL_CONFIG = """[v3_ca]
basicConstraints = critical,CA:TRUE
[v3_server]
basicConstraints = critical,CA:FALSE
extendedKeyUsage = serverAuth
subjectAltName = DNS:localhost,IP:127.0.0.1
[v3_client]
basicConstraints = critical,CA:FALSE
extendedKeyUsage = clientAuth
"""

class SetupError(RuntimeError): pass
def tail(text, limit=3000):
    return (text or "").strip()[-limit:]

def run(argv, timeout, *, cwd=None, label=None):
    """Run one argv list with a hard timeout; no shell is involved."""
    try:
        done = subprocess.run([str(item) for item in argv], cwd=cwd, capture_output=True,
                              text=True, timeout=timeout)
    except (subprocess.TimeoutExpired, OSError) as exc:
        raise SetupError("%s failed: %s" % (label or argv[0], exc)) from exc
    return done.returncode, done.stdout or "", done.stderr or ""
def git(root, *args):
    code, out, err = run(["git", "-C", str(root), *args], 30, label="git " + args[0])
    if code != 0:
        raise SetupError("git %s failed: %s" % (" ".join(args), tail(err)))
    return out
def sha256_file(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
def resolve_root(value):
    if not value:
        raise SetupError("Leviathan root required; use --leviathan-root or LEVIATHAN_ROOT")
    supplied = Path(value).expanduser().resolve()
    top = Path(git(supplied, "rev-parse", "--show-toplevel").strip()).resolve()
    if top != supplied:
        raise SetupError("resolved Git root %s != supplied path %s" % (top, supplied))
    return top
def collect_sources(root):
    peer_dir = root / PEER_DIR
    peers = sorted(peer_dir.glob("*.java")) if peer_dir.is_dir() else []
    if not peers:
        raise SetupError("peer package has no *.java sources: %s" % peer_dir)
    sources = peers + [root / item for item in SUPPORT]
    missing = [path.relative_to(root).as_posix() for path in sources if not path.is_file()]
    if missing:
        raise SetupError("peer sources are missing: %s" % ", ".join(missing))
    files = [{"path": path.relative_to(root).as_posix(), "sha256": sha256_file(path),
              "bytes": path.stat().st_size} for path in sources]
    digest = hashlib.sha256("".join("%s:%s\n" % (item["path"], item["sha256"])
                                    for item in files).encode("utf-8")).hexdigest()
    return sources, files, digest

def read_classpath(path):
    """Accept colon- or newline-separated exported classpath text; reject missing entries."""
    if not path.is_file():
        raise SetupError("classpath file does not exist: %s" % path)
    text = path.read_text(encoding="utf-8", errors="replace")
    entries = [Path(value.strip().strip("\"'")).expanduser() for value
               in re.split(r"[:\n]", text) if value.strip() and not value.strip().startswith("#")]
    missing = [str(entry) for entry in entries if not entry.exists()]
    if missing:
        raise SetupError("classpath entries do not exist: %s" % ", ".join(missing[:10]))
    if not entries:
        raise SetupError("classpath file is empty: %s" % path)
    return [entry.resolve() for entry in entries]
def find_java(value):
    raw = value or os.environ.get("JAVA_HOME")
    if raw:
        home = Path(raw).expanduser()
        java, javac = home / "bin/java", home / "bin/javac"
        if java.is_file() and javac.is_file():
            return java, javac, str(home)
        raise SetupError("Java home has no bin/java and bin/javac: %s" % home)
    java, javac = shutil.which("java"), shutil.which("javac")
    if java and javac:
        return Path(java), Path(javac), None
    raise SetupError("java/javac not found; pass --java-home or set JAVA_HOME")

def openssl(binary, args, cwd, label):
    code, out, err = run([binary, *args], 60, cwd=cwd, label="openssl " + label)
    if code != 0:
        raise SetupError("openssl %s failed: %s" % (label, tail(err or out)))
    return out
def generate_certs(directory, binary):
    directory.mkdir(parents=True, exist_ok=True)
    config = directory / "openssl.cnf"
    config.write_text(OPENSSL_CONFIG, encoding="utf-8")
    ca_key, ca_pem = directory / "ca-key.pem", directory / "ca.pem"
    openssl(binary, ["genrsa", "-out", ca_key, "2048"], directory, "CA key")
    openssl(binary, ["req", "-x509", "-new", "-sha256", "-days", "2", "-key", ca_key,
                     "-subj", "/CN=SourbyCraft AbyssLink Interop CA",
                     "-addext", "basicConstraints=critical,CA:TRUE", "-out", ca_pem],
            directory, "CA certificate")
    for label, cn, ext in (("server", "localhost", "v3_server"),
                           ("client", "backend-01", "v3_client")):
        raw, key = directory / (label + "-raw.pem"), directory / (label + "-key.pem")
        cert, csr = directory / (label + ".pem"), directory / (label + ".csr")
        openssl(binary, ["genrsa", "-out", raw, "2048"], directory, label + " key")
        openssl(binary, ["pkcs8", "-topk8", "-nocrypt", "-in", raw, "-out", key],
                directory, label + " PKCS#8")
        raw.unlink()
        openssl(binary, ["req", "-new", "-key", key, "-subj", "/CN=" + cn,
                         "-out", csr], directory, label + " CSR")
        serial = ["-CAserial", directory / "ca.srl"] if (directory / "ca.srl").exists() \
            else ["-CAcreateserial"]
        openssl(binary, ["x509", "-req", "-in", csr, "-CA", ca_pem, "-CAkey", ca_key,
                         *serial, "-days", "2", "-sha256", "-extfile", config,
                         "-extensions", ext, "-out", cert], directory, label + " certificate")
    ca_key.unlink()
    for label, purpose in (("server", "sslserver"), ("client", "sslclient")):
        openssl(binary, ["verify", "-CAfile", ca_pem, "-purpose", purpose,
                         directory / (label + ".pem")], directory, "verify " + label)
    server_text = openssl(binary, ["x509", "-in", directory / "server.pem", "-noout", "-text"],
                          directory, "inspect server")
    normalized = server_text.replace(" ", "")
    if "DNS:localhost" not in server_text or "IPAddress:127.0.0.1" not in normalized:
        raise SetupError("server certificate SAN missing localhost/127.0.0.1")
    return {"ca_sha256": sha256_file(ca_pem),
            "server_cert_sha256": sha256_file(directory / "server.pem"),
            "client_cert_sha256": sha256_file(directory / "client.pem"),
            "server_san": ["DNS:localhost", "IP:127.0.0.1"], "server_eku": "serverAuth",
            "client_eku": "clientAuth", "keys_persisted": False}

def compile_harness(javac, classpath, sources, classes):
    classes.mkdir(parents=True, exist_ok=True)
    code, out, err = run([javac, "-encoding", "UTF-8", "-d", classes, "-cp",
                          os.pathsep.join(map(str, classpath)), *map(str, sources), HARNESS],
                         300, label="javac")
    if code != 0:
        raise SetupError("javac failed with exit %s: %s" % (code, tail(err or out)))
def parse_checks(text):
    found = {}
    for line in (text or "").splitlines():
        parts = line.split(" ", 3)
        if len(parts) == 4 and parts[0] == "CHECK" and parts[2] in ("PASS", "FAIL"):
            found[parts[1]] = (parts[2] == "PASS", parts[3].strip())
    return found
def write_report(path, report):
    if path:
        destination = Path(path).expanduser()
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
def parse_args(argv=None):
    parser = argparse.ArgumentParser(prog="run_interop.py")
    for flag in ("--leviathan-root", "--java-home", "--report-json"):
        parser.add_argument(flag)
    parser.add_argument("--classpath-file", required=True)
    parser.add_argument("--timeout", type=int, default=180)
    return parser.parse_args(argv)

def main(argv=None):
    args = parse_args(argv)
    checks = {}
    report = {"schema": SCHEMA, "ok": False, "exit_code": None, "checks": checks,
              "required_checks": list(REQUIRED), "contains_secrets": False}
    exit_code = 2
    def record(name, ok, detail=""):
        checks[name] = {"status": "PASS" if ok else "FAIL", "detail": tail(str(detail), 2000)}
    try:
        if args.timeout < 1:
            raise SetupError("--timeout must be positive")
        if not HARNESS.is_file():
            raise SetupError("harness source is missing: %s" % HARNESS)
        root = resolve_root(args.leviathan_root or os.environ.get("LEVIATHAN_ROOT"))
        status = git(root, "status", "--porcelain=v1", "--untracked-files=normal")
        report["leviathan"] = {
            "root": str(root), "git_head": git(root, "rev-parse", "HEAD").strip(),
            "git_dirty": bool(status.strip()),
            "git_status_sha256": hashlib.sha256(status.encode("utf-8")).hexdigest()}
        record("git-root", True, "resolved Git root: %s" % root)
        sources, files, digest = collect_sources(root)
        report["leviathan"].update(sources=files, source_digest_sha256=digest)
        record("sources-present", True, "%d peer + %d support sources; digest %s"
               % (len(sources) - len(SUPPORT), len(SUPPORT), digest))
        cp_file = Path(args.classpath_file).expanduser()
        classpath = read_classpath(cp_file)
        report["classpath"] = {"file": str(cp_file.resolve()), "sha256": sha256_file(cp_file),
                               "entry_count": len(classpath)}
        record("classpath", True, "%d existing entries" % len(classpath))
        java, javac, home = find_java(args.java_home)
        openssl_bin = shutil.which("openssl")
        if not openssl_bin:
            raise SetupError("openssl not found on PATH")
        report["java"] = {"home": home, "exit_code": None}
        with tempfile.TemporaryDirectory(prefix="abysslink-interop-") as temporary:
            cert_dir = Path(temporary) / "certs"
            report["certificates"] = generate_certs(cert_dir, openssl_bin)
            record("certificates", True, "temporary CA; SAN localhost + 127.0.0.1; EKUs")
            classes = Path(temporary) / "classes"
            compile_harness(javac, classpath, sources, classes)
            report["compile"] = {"source_files": len(sources) + 1,
                                 "runner_sha256": sha256_file(Path(__file__).resolve()),
                                 "harness_sha256": sha256_file(HARNESS)}
            record("compile", True, "%d sources plus harness compiled" % (len(sources) + 1))
            code, stdout, stderr = run([java, "-Xmx256m", "-Djava.awt.headless=true", "-cp",
                os.pathsep.join([str(classes), *map(str, classpath)]),
                "AbyssLinkInteropHarness", cert_dir], args.timeout, label="harness")
            report["java"].update(exit_code=code, stderr_tail=tail(stderr))
            for name, (passed, detail) in parse_checks(stdout).items():
                record(name, passed, detail)
            record("java-harness", code == 0, "exit=%s" % code)
            exit_code = 0 if code == 0 else 1
    except SetupError as exc:
        record("setup", False, str(exc))
        exit_code = 2
    except Exception as exc:  # A runner bug must never look like a pass.
        record("internal-error", False, "%s: %s" % (type(exc).__name__, exc))
        exit_code = 2
    missing = [name for name in REQUIRED if name not in checks]
    failed = [name for name in REQUIRED if name in checks and checks[name]["status"] != "PASS"]
    if exit_code == 0 and (missing or failed):
        exit_code = 1
    report.update(missing_checks=missing, failed_checks=failed, exit_code=exit_code,
                  ok=exit_code == 0)
    try:
        write_report(args.report_json, report)
    except OSError as exc:
        print("error: could not write report: %s" % exc, file=sys.stderr)
        report["ok"] = False
        exit_code = 2
    print("AbyssLink real-peer interoperability: %s (%d/%d checks)"
          % ("PASS" if report["ok"] else "FAIL",
             len(REQUIRED) - len(failed) - len(missing), len(REQUIRED)))
    for name in sorted(set(missing) | set(failed)):
        print("  FAIL %-26s %s" % (name, checks.get(name, {}).get("detail", "missing")))
    return exit_code

if __name__ == "__main__":
    sys.exit(main())
