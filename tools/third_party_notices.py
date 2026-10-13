"""Writes licenses/THIRD-PARTY-NOTICES.txt: every third-party component the apps ship, with license texts.

Sources:
  - Rust crates linked into native/airlink (Windows + Android builds): `cargo metadata`, and the license
    files inside each crate's source in the local cargo registry.
  - .NET packages in the PC app: `dotnet list package --include-transitive`, and the license / notice files
    inside each package in the local NuGet cache.
  - Android libraries (Kotlin standard library, ZXing) and the bundled assets (Lucide icons, Inter font):
    listed below by hand.

Run from the repo root after building once (so the caches are filled):
  python tools/third_party_notices.py
"""
import hashlib
import json
import os
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "licenses", "THIRD-PARTY-NOTICES.txt")
NUGET = os.path.join(os.path.expanduser("~"), ".nuget", "packages")
RUST_TARGETS = ["x86_64-pc-windows-msvc", "aarch64-linux-android", "armv7-linux-androideabi"]
# Build-time only, not part of the shipped apps.
NUGET_SKIP = {"avalonia.buildservices"}
LICENSE_FILE = re.compile(r"^(licen[sc]e|copying|notice|copyright|third-party-notices)", re.I)

RULE = "=" * 78


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read().replace("\r\n", "\n").strip()


def rust_crates():
    """(name, version, license, repository, [license files]) for crates linked into airlink."""
    crates = {}
    for target in RUST_TARGETS:
        meta = json.loads(subprocess.check_output(
            ["cargo", "metadata", "--format-version", "1", "--filter-platform", target],
            cwd=os.path.join(ROOT, "native", "airlink")))
        packages = {p["id"]: p for p in meta["packages"]}
        nodes = {n["id"]: n for n in meta["resolve"]["nodes"]}
        seen, stack = set(), [meta["resolve"]["root"]]
        while stack:
            node = stack.pop()
            if node in seen:
                continue
            seen.add(node)
            for dep in nodes[node]["deps"]:
                if any(k["kind"] in (None, "normal") for k in dep["dep_kinds"]):
                    stack.append(dep["pkg"])
        for node in seen:
            p = packages[node]
            if p["name"] == "airlink":
                continue
            folder = os.path.dirname(p["manifest_path"])
            files = sorted(os.path.join(folder, f) for f in os.listdir(folder)
                           if LICENSE_FILE.match(f) and os.path.isfile(os.path.join(folder, f)))
            crates[(p["name"], p["version"])] = (p.get("license") or "see files", p.get("repository") or "", files)
    return crates


def nuget_packages():
    out = subprocess.check_output(
        ["dotnet", "list", "package", "--include-transitive", "--framework", "net10.0"],
        cwd=os.path.join(ROOT, "desktop", "AirPointer"), text=True)
    packages = {}
    for line in out.splitlines():
        m = re.match(r"\s*>\s+(\S+)\s+.*?(\d+\.\d+[\w.\-]*)\s*$", line)
        if not m or m.group(1).lower() in NUGET_SKIP:
            continue
        name, version = m.group(1), m.group(2)
        folder = os.path.join(NUGET, name.lower(), version)
        nuspec = next((os.path.join(folder, f) for f in os.listdir(folder) if f.endswith(".nuspec")), None)
        info = {"license": "", "copyright": "", "url": ""}
        if nuspec:
            xml = read(nuspec)
            # Downloaded data: refuse DTDs/entities (XXE, entity expansion) before parsing.
            if "<!DOCTYPE" in xml or "<!ENTITY" in xml:
                raise ValueError(f"unexpected DTD in {nuspec}")
            for el in ET.fromstring(xml).iter():
                tag = el.tag.split("}")[-1]
                if tag == "license":
                    info["license"] = el.text or ""
                elif tag in ("copyright", "projectUrl") and el.text:
                    info["copyright" if tag == "copyright" else "url"] = re.sub(r"\?utm_.*", "", el.text.strip())
        files = sorted(os.path.join(folder, f) for f in os.listdir(folder)
                       if LICENSE_FILE.match(f) and os.path.isfile(os.path.join(folder, f)))
        packages[(name, version)] = (info, files)
    return packages


MIT = """Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the
following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
USE OR OTHER DEALINGS IN THE SOFTWARE."""


def main():
    crates = rust_crates()
    packages = nuget_packages()
    # The Apache-2.0 text, taken from a crate that ships it (used for the Android libraries below).
    apache = next((read(f) for _, _, files in crates.values() for f in files
                   if "apache" in os.path.basename(f).lower()), "")

    parts = [f"""AirPointer - third-party notices
{RULE}

AirPointer itself is released under the MIT License (see LICENSE in the repository root).
The apps include the open-source components listed below. Each is used under its own license, reproduced
or referenced here. Thank you to all of their authors.

Contents
  1. Bundled assets (icons, font)
  2. Android app libraries
  3. PC app: .NET runtime and packages
  4. Native "airlink" library (internet connections, Windows H.264 encoder): Rust crates
  5. OpenH264 (not included; downloaded from Cisco on request)
"""]

    parts.append(f"""
{RULE}
1. Bundled assets
{RULE}

Lucide icons (phone and PC apps) - ISC License (some icons MIT, from Feather)
https://lucide.dev
{read(os.path.join(ROOT, "licenses", "Lucide-LICENSE.txt"))}

Inter typeface (PC app, via Avalonia.Fonts.Inter) - SIL Open Font License 1.1
Copyright (c) 2016 The Inter Project Authors (https://github.com/rsms/inter)
The font files carry their license in their own metadata. License text: https://openfontlicense.org
""")

    parts.append(f"""
{RULE}
2. Android app libraries
{RULE}

Kotlin standard library - Apache License 2.0 - Copyright JetBrains s.r.o. and Kotlin Programming Language
contributors - https://github.com/JetBrains/kotlin
JetBrains annotations - Apache License 2.0 - Copyright JetBrains s.r.o. - https://github.com/JetBrains/java-annotations
ZXing "core" 3.5.3 (QR code scanning) - Apache License 2.0 - Copyright ZXing authors - https://github.com/zxing/zxing

Apache License 2.0 text:

{apache}
""")

    lines = [f"""
{RULE}
3. PC app: .NET runtime and packages
{RULE}

.NET runtime (included in the self-contained PC app) - MIT License - Copyright (c) .NET Foundation and
Contributors - https://github.com/dotnet/runtime
Its own third-party notices: https://github.com/dotnet/runtime/blob/main/THIRD-PARTY-NOTICES.TXT
"""]
    texts = {}
    for (name, version), (info, files) in sorted(packages.items(), key=lambda kv: kv[0][0].lower()):
        lines.append(f"{name} {version} - {info['license'] or 'see license file'}"
                     + (f" - {info['copyright']}" if info["copyright"] else "")
                     + (f" - {info['url']}" if info["url"] else ""))
        for f in files:
            texts.setdefault(read(f), []).append(f"{name} {version} ({os.path.basename(f)})")
    lines.append(f"\nMIT License text (for the packages above marked MIT, with their copyright lines above):\n\n{MIT}\n")
    for text, owners in texts.items():
        lines.append(f"--- {', '.join(owners)} ---\n\n{text}\n")
    parts.append("\n".join(lines))

    lines = [f"""
{RULE}
4. Native "airlink" library: Rust crates
{RULE}

Linked into airlink.dll (Windows) and libairlink.so (Android). Where a crate offers a choice of licenses
(for example "MIT OR Apache-2.0"), AirPointer uses it under any of them. Source for every crate is on
https://crates.io under its name and version.
"""]
    texts = {}
    for (name, version), (lic, repo, files) in sorted(crates.items()):
        lines.append(f"{name} {version} - {lic}" + (f" - {repo}" if repo else ""))
        for f in files:
            body = read(f)
            texts.setdefault(hashlib.sha256(body.encode()).hexdigest(), (body, []))[1].append(f"{name} {version}")
    lines.append("\nLicense texts, as shipped by the crates (each followed by the crates it comes from):\n")
    for body, owners in texts.values():
        lines.append(f"--- {', '.join(owners)} ---\n\n{body}\n")
    missing = [f"{n} {v} ({lic})" for (n, v), (lic, _, files) in sorted(crates.items()) if not files]
    if missing:
        lines.append("Crates that ship no license file (their license is the standard text of the license named):\n  "
                     + "\n  ".join(missing) + "\n")
    parts.append("\n".join(lines))

    parts.append(f"""
{RULE}
5. OpenH264
{RULE}

OpenH264 Video Codec provided by Cisco Systems, Inc.
Not included in AirPointer. On Linux and macOS, if the user turns on "Smooth video", the PC app downloads
Cisco's binary from Cisco, under Cisco's license (licenses/OpenH264-BINARY_LICENSE.txt, also shown by the
app). Cisco pays the MPEG LA H.264 royalties for that binary.
""")

    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(parts))
    print(f"{OUT}: {len(crates)} crates, {len(packages)} NuGet packages")


main()
