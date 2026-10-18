#!/usr/bin/env python3
"""Repair this Debian cloud image's JRE-only Java 21 without root or weakening verification.

Other platforms should install any complete, trusted JDK 21 and set JAVA_HOME.
This helper deliberately requires the matching Debian runtime and archive keyring.
"""
import hashlib
import json
import lzma
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile


VERSION = "21.0.12.1+1-1~deb13u1"
SHA256 = "f3abafb6c644b03df042824e707cd211ea33254761d7f7b75be3f8dc0df97c7a"
PACKAGE = "pool/main/o/openjdk-21/openjdk-21-jdk-headless_" + VERSION + "_amd64.deb"
BASE = "https://deb.debian.org/debian/"


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def complete_jdk(directory):
    directory = Path(directory)
    if not (directory / "bin/javac").is_file() or not (directory / "lib/ct.sym").is_file():
        return False
    result = subprocess.run([str(directory / "bin/javac"), "--release", "21", "-version"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return result.returncode == 0 and b"javac 21." in result.stdout + result.stderr


def download(url, destination):
    run("curl", "--fail", "--silent", "--show-error", "--location", "--proto", "=https",
        "--tlsv1.2", "--connect-timeout", "10", "--max-time", "180", url, "--output", destination)


def main():
    target = Path(os.environ.get("CHARGEHUB_TOOLS_DIR", "/workspace/tools")) / "jdk-21"
    selected = os.environ.get("JAVA_HOME")
    for candidate in ([Path(selected)] if selected else []) + [target]:
        if complete_jdk(candidate):
            print("Complete JDK 21 available:", candidate)
            return
    if target.exists():
        raise SystemExit(f"Refusing to overwrite existing {target}; select a complete JDK 21 with JAVA_HOME.")
    runtime = Path("/usr/lib/jvm/java-21-openjdk-amd64")
    if platform.machine() != "x86_64" or not (runtime / "release").is_file():
        raise SystemExit("This repair supports the supplied Debian amd64 runtime only; install a complete JDK 21.")
    release = (runtime / "release").read_text()
    if 'JAVA_VERSION="21.0.12.1"' not in release or 'IMPLEMENTOR="Debian"' not in release:
        raise SystemExit("Runtime does not match the pinned Debian JDK; install a complete trusted JDK 21 instead.")
    keyring = Path("/usr/share/keyrings/debian-archive-keyring.gpg")
    if not keyring.is_file():
        raise SystemExit("Trusted Debian archive keyring missing; no unverified bootstrap is performed.")
    for executable in ("curl", "gpg", "dpkg-deb"):
        if not shutil.which(executable):
            raise SystemExit(f"Required verified system tool missing: {executable}")
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="chargehub-jdk21-") as temporary:
        work = Path(temporary)
        gnupg = work / "gnupg"
        gnupg.mkdir(mode=0o700)
        download(BASE + "dists/trixie/InRelease", work / "InRelease")
        # Decryption extracts the clear-signed Release only after GPG checks its signatures.
        run("gpg", "--batch", "--no-options", "--homedir", gnupg, "--no-default-keyring",
            "--keyring", keyring, "--output", work / "Release", "--decrypt", work / "InRelease")
        download(BASE + "dists/trixie/main/binary-amd64/Packages.xz", work / "Packages.xz")
        lines = (work / "Release").read_text().split("SHA256:\n", 1)[1].split("\nSHA", 1)[0].splitlines()
        digest, size, _ = next(line.split() for line in lines if line.endswith("main/binary-amd64/Packages.xz"))
        compressed = (work / "Packages.xz").read_bytes()
        if hashlib.sha256(compressed).hexdigest() != digest or len(compressed) != int(size):
            raise SystemExit("Signed Debian Packages index checksum mismatch.")
        package = None
        for paragraph in lzma.decompress(compressed).decode().split("\n\n"):
            if paragraph.startswith("Package: openjdk-21-jdk-headless\n"):
                package = dict(line.split(": ", 1) for line in paragraph.splitlines()
                               if ": " in line and not line.startswith(" "))
                break
        if not package or package["Version"] != VERSION or package["SHA256"] != SHA256 or package["Filename"] != PACKAGE:
            raise SystemExit("Pinned JDK is no longer in the signed index; install a full JDK 21 from a trusted provider.")
        archive = work / "jdk.deb"
        download(BASE + PACKAGE, archive)
        payload = archive.read_bytes()
        if hashlib.sha256(payload).hexdigest() != SHA256 or len(payload) != int(package["Size"]):
            raise SystemExit("JDK package checksum mismatch.")
        unpacked = work / "unpacked"
        run("dpkg-deb", "--extract", archive, unpacked)
        assembled = work / "jdk-21"
        shutil.copytree(runtime, assembled, symlinks=True)
        shutil.copytree(unpacked / "usr/lib/jvm/java-21-openjdk-amd64", assembled,
                        symlinks=True, dirs_exist_ok=True)
        if not complete_jdk(assembled):
            raise SystemExit("Assembled JDK did not pass javac --release 21 validation.")
        receipt = {"version": VERSION, "source": BASE + PACKAGE, "sha256": SHA256,
                   "verification": ["Debian archive keyring validated InRelease signatures",
                                    "Signed Packages.xz SHA256 and size validated",
                                    "Pinned .deb SHA256 and size validated"],
                   "runtimeBase": str(runtime)}
        (assembled / "chargehub-verification.json").write_text(json.dumps(receipt, indent=2) + "\n")
        shutil.move(str(assembled), str(target))
    print("Complete verified JDK 21 installed:", target)
    print(f"export JAVA_HOME={target}")
    print('export PATH="$JAVA_HOME/bin:$PATH"')


if __name__ == "__main__":
    main()
