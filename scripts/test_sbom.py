import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
import zipfile

import sbom

ROOT = Path(__file__).resolve().parent.parent


def sha(data):
    return hashlib.sha256(data).hexdigest()


LIB_A = b"library a bytes"
LIB_B = b"library b bytes"
LIB_N = b"native"
SERVER_PROPS = ("version=26.2-REL\nbuild=47\nbuildNumber=47\ngitCommit=" + "a" * 40 + "\ntreeHash=" + "b" * 64
                + "\ndirty=false\nbuildTimestamp=2026-10-20T00:00:00Z\nprovenance.Jdk=Eclipse Adoptium 25.0.1+8-LTS\n")


def server_jar(directory):
    path = Path(directory) / "sourbycraft-server-26.2-REL.jar"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("META-INF/sourbycraft-build.properties", SERVER_PROPS)
    return path


def slim_jar(directory, server_sha, strip_b=True, extra_line=None):
    path = Path(directory) / "SourbyCraft-slim.jar"
    lines = [
        f"{sha(LIB_A)}\tcom.example:alpha:1.0\tcom/example/alpha/1.0/alpha-1.0.jar",
        f"{sha(LIB_B)}\torg.example:beta:2.0\torg/example/beta/2.0/beta-2.0.jar",
        f"{sha(LIB_N)}\tio.netty:native:4.2:linux-x86_64\tio/netty/native/4.2/native-4.2-linux-x86_64.jar",
    ]
    if extra_line:
        lines.append(extra_line)
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMain-Class: dev.iyanz.sourbyclip.Main\r\n"
                                                 "Clip-Version: 3.1.2\r\nSourbyClip-Protocol: 1\r\n")
        archive.writestr("META-INF/libraries.list", "\n".join(lines) + "\n")
        archive.writestr("META-INF/versions.list", f"{server_sha}\t26.2\t26.2/sourbycraft-26.2.jar\n")
        archive.writestr("META-INF/download-context", f"{'c' * 64}\thttps://piston-data.mojang.com/server.jar\tmojang_26.2.jar")
        archive.writestr("META-INF/libraries/com/example/alpha/1.0/alpha-1.0.jar", LIB_A)
        if not strip_b:
            archive.writestr("META-INF/libraries/org/example/beta/2.0/beta-2.0.jar", LIB_B)
    return path


class SbomTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.server = server_jar(self.tmp.name)
        self.jar = slim_jar(self.tmp.name, sha(self.server.read_bytes()))

    def tearDown(self):
        self.tmp.cleanup()

    def library(self, bom, ref):
        return next(c for c in bom["components"] if c.get("bom-ref") == ref)

    def test_every_libraries_list_line_becomes_one_verified_component(self):
        bom = sbom.generate(self.jar, self.server)
        self.assertEqual({"libraries": 3, "bundled": 1, "components": 6}, sbom.verify(self.jar, bom))
        alpha = self.library(bom, "pkg:maven/com.example/alpha@1.0")
        self.assertEqual([{"alg": "SHA-256", "content": sha(LIB_A)}], alpha["hashes"])
        self.assertEqual("bundled", sbom._property(alpha, sbom.PROPERTY_DELIVERY))
        beta = self.library(bom, "pkg:maven/org.example/beta@2.0")
        self.assertEqual("not-bundled", sbom._property(beta, sbom.PROPERTY_DELIVERY))
        native = self.library(bom, "pkg:maven/io.netty/native@4.2?classifier=linux-x86_64")
        self.assertEqual("4.2", native["version"])

    def test_metadata_names_the_jar_and_carries_build_identity_and_provenance(self):
        # CI exports SOURCE_DATE_EPOCH (reproducible builds), which takes precedence over the
        # jar's buildTimestamp; this case pins the buildTimestamp fallback, so it runs without it.
        saved = os.environ.pop("SOURCE_DATE_EPOCH", None)
        try:
            bom = sbom.generate(self.jar, self.server)
        finally:
            if saved is not None:
                os.environ["SOURCE_DATE_EPOCH"] = saved
        root = bom["metadata"]["component"]
        self.assertEqual(sha(self.jar.read_bytes()), root["hashes"][0]["content"])
        self.assertEqual("Build 47", root["version"])
        props = {p["name"]: p["value"] for p in root["properties"]}
        self.assertEqual("a" * 40, props["sourbycraft:gitCommit"])
        self.assertEqual("Eclipse Adoptium 25.0.1+8-LTS", props["sourbycraft:provenance.Jdk"])
        self.assertEqual("2026-10-20T00:00:00Z", bom["metadata"]["timestamp"])
        names = [c["name"] for c in bom["components"]]
        self.assertIn("sourbyclip", names)
        self.assertIn("minecraft-server", names)

    def test_output_is_deterministic_for_the_same_jar(self):
        self.assertEqual(json.dumps(sbom.generate(self.jar, self.server)), json.dumps(sbom.generate(self.jar, self.server)))

    def test_a_server_jar_that_is_not_the_one_in_versions_list_is_refused(self):
        other = Path(self.tmp.name) / "other.jar"
        with zipfile.ZipFile(other, "w") as archive:
            archive.writestr("META-INF/sourbycraft-build.properties", SERVER_PROPS + "x=1\n")
        with self.assertRaisesRegex(ValueError, "not the server jar"):
            sbom.generate(self.jar, other)

    def test_verify_rejects_any_drift_from_libraries_list(self):
        bom = sbom.generate(self.jar, self.server)
        cases = {}
        missing = copy.deepcopy(bom)
        missing["components"] = [c for c in missing["components"] if c.get("name") != "beta"]
        missing["dependencies"][0]["dependsOn"] = [c["bom-ref"] for c in missing["components"]]
        cases["missing library"] = missing
        wrong_hash = copy.deepcopy(bom)
        self.library(wrong_hash, "pkg:maven/com.example/alpha@1.0")["hashes"][0]["content"] = "0" * 64
        cases["wrong hash"] = wrong_hash
        extra = copy.deepcopy(bom)
        extra["components"].append({"type": "library", "bom-ref": "pkg:maven/x/y@1", "purl": "pkg:maven/x/y@1", "name": "y",
                                    "version": "1", "properties": [{"name": sbom.PROPERTY_DELIVERY, "value": "bundled"}]})
        extra["dependencies"][0]["dependsOn"].append("pkg:maven/x/y@1")
        cases["extra library"] = extra
        delivery = copy.deepcopy(bom)
        beta = self.library(delivery, "pkg:maven/org.example/beta@2.0")
        beta["properties"] = [{"name": sbom.PROPERTY_DELIVERY, "value": "bundled"}, beta["properties"][1]]
        cases["wrong delivery"] = delivery
        root_hash = copy.deepcopy(bom)
        root_hash["metadata"]["component"]["hashes"][0]["content"] = "1" * 64
        cases["root hash"] = root_hash
        spec = copy.deepcopy(bom)
        spec["specVersion"] = "1.4"
        cases["spec version"] = spec
        dangling = copy.deepcopy(bom)
        dangling["dependencies"][0]["dependsOn"].pop()
        cases["dependencies"] = dangling
        for name, broken in cases.items():
            with self.subTest(name), self.assertRaises(ValueError):
                sbom.verify(self.jar, broken)

    def test_an_sbom_of_another_jar_does_not_verify(self):
        bom = sbom.generate(self.jar, self.server)
        with tempfile.TemporaryDirectory() as other:
            rebuilt = slim_jar(other, sha(self.server.read_bytes()), strip_b=False)
            with self.assertRaises(ValueError):
                sbom.verify(rebuilt, bom)

    def test_malformed_lists_and_duplicates_fail_generation(self):
        with tempfile.TemporaryDirectory() as other:
            dup = slim_jar(other, sha(self.server.read_bytes()),
                           extra_line=f"{sha(LIB_A)}\tcom.example:alpha:1.0\tcom/example/alpha/1.0/alpha-1.0.jar")
            with self.assertRaisesRegex(ValueError, "twice"):
                sbom.generate(dup)
        with tempfile.TemporaryDirectory() as other:
            bad = slim_jar(other, sha(self.server.read_bytes()), extra_line="nothex\tx:y:1\tx/y.jar")
            with self.assertRaisesRegex(ValueError, "libraries.list"):
                sbom.generate(bad)

    def test_cli_generate_then_verify(self):
        out = Path(self.tmp.name) / "sbom.json"
        self.assertEqual(0, sbom.main(["generate", str(self.jar), "--server-jar", str(self.server), "-o", str(out)]))
        self.assertEqual(0, sbom.main(["verify", str(self.jar), str(out)]))

    @unittest.skipUnless((ROOT / "build/libs/SourbyCraft-slim.jar").is_file(), "no locally built slim jar")
    def test_the_locally_built_slim_jar(self):
        jar = ROOT / "build/libs/SourbyCraft-slim.jar"
        result = sbom.verify(jar, sbom.generate(jar))
        with zipfile.ZipFile(jar) as archive:
            lines = [l for l in archive.read("META-INF/libraries.list").decode().splitlines() if l.strip()]
        self.assertEqual(len(lines), result["libraries"])


if __name__ == "__main__":
    unittest.main()
