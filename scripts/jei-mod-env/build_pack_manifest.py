"""Build the tracked mod-pack manifest from an installed `run/mods`.

The two harnesses (`scripts/jei-client-tests.ps1`, `scripts/jei-declared-smoke.ps1`)
load the 76-file pack in `targets/neoforge-1.21.1/run/mods`, which `run/` being
gitignored keeps out of a fresh clone. The historic assembler (`prune.py`) needs
two inputs no tracked script produces, so this takes a different route: it
identifies every installed jar by its **sha1** through Modrinth's
`/v2/version_files`, which answers with the version the file really came from -
so no project slug, file name or dependency graph has to be guessed - and writes
`pack-manifest.json`, the machine-readable list `download_pack.py` downloads
from on a fresh clone.

Run it on a machine that still has the installed pack:

    python scripts/jei-mod-env/build_pack_manifest.py

It also cross-checks the manifest against the file list in this directory's
README, which is the human-readable record of the same set, and fails if the two
disagree - that list and this manifest are the pack's two descriptions, so they
must not drift apart.
"""
import hashlib
import json
import os
import re
import sys
import urllib.parse
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MODS = os.path.join(ROOT, "targets", "neoforge-1.21.1", "run", "mods")
DOC = os.path.join(ROOT, "scripts", "jei-mod-env", "README.md")
OUT = os.path.join(ROOT, "scripts", "jei-mod-env", "pack-manifest.json")
UA = {"User-Agent": "jei-editor-research/1.0"}
DISABLED_SUFFIX = ".jar.disabled"


def post(url, payload):
    request = urllib.request.Request(
        url, data=json.dumps(payload).encode("utf-8"),
        headers=dict(UA, **{"Content-Type": "application/json"}))
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def get(url):
    request = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def sha1(path):
    digest = hashlib.sha1()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def installed_files():
    """The installed pack, in name order: (installed name, disabled, path)."""
    if not os.path.isdir(MODS):
        raise SystemExit("the installed pack is missing: %s" % MODS)
    found = []
    for name in sorted(os.listdir(MODS)):
        path = os.path.join(MODS, name)
        if not os.path.isfile(path):
            continue
        found.append((name, name.endswith(DISABLED_SUFFIX), path))
    return found


def documented_names():
    """The file names the README lists under "The 76 target files"."""
    with open(DOC, encoding="utf-8") as handle:
        text = handle.read()
    heading = text.index("### The 76 target files")
    blocks = re.findall(r"```text\n(.*?)```", text[heading:], re.S)
    names = []
    for block in blocks:
        for line in block.splitlines():
            line = line.strip()
            if line.endswith(".jar") or line.endswith(DISABLED_SUFFIX):
                names.append(line)
    return names


def main():
    files = installed_files()
    print("installed files: %d (%d active, %d disabled)"
          % (len(files), sum(1 for _, disabled, _ in files if not disabled),
             sum(1 for _, disabled, _ in files if disabled)))

    by_hash = {}
    for name, disabled, path in files:
        by_hash[sha1(path)] = (name, disabled, path)

    # One bulk lookup for the whole pack: Modrinth answers with the version each
    # file belongs to, keyed by the hash we sent.
    versions = post("https://api.modrinth.com/v2/version_files",
                    {"hashes": sorted(by_hash), "algorithm": "sha1"})
    print("resolved by sha1: %d of %d" % (len(versions), len(by_hash)))

    projects = {}
    project_ids = sorted({v["project_id"] for v in versions.values()})
    for start in range(0, len(project_ids), 50):
        chunk = project_ids[start:start + 50]
        # The ids go in the query string, so the JSON array must be encoded:
        # json.dumps alone leaves spaces in it and urllib refuses the URL.
        ids = urllib.parse.quote(json.dumps(chunk, separators=(",", ":")))
        for project in get("https://api.modrinth.com/v2/projects?ids=" + ids):
            projects[project["id"]] = project

    entries = []
    unresolved = []
    for digest, (name, disabled, path) in sorted(by_hash.items(), key=lambda kv: kv[1][0]):
        version = versions.get(digest)
        if version is None:
            unresolved.append(name)
            entries.append({"name": name, "disabled": disabled, "url": None,
                            "sha1": digest, "size": os.path.getsize(path)})
            continue
        file = next((f for f in version["files"] if f["hashes"].get("sha1") == digest), None)
        if file is None:
            unresolved.append(name)
            entries.append({"name": name, "disabled": disabled, "url": None,
                            "sha1": digest, "size": os.path.getsize(path)})
            continue
        project = projects.get(version["project_id"], {})
        entries.append({
            "name": name,
            "disabled": disabled,
            "project": project.get("slug"),
            "title": project.get("title"),
            "project_id": version["project_id"],
            "version_id": version["id"],
            "version_number": version["version_number"],
            "filename": file["filename"],
            "sha1": digest,
            "sha512": file["hashes"].get("sha512"),
            "size": file["size"],
            "url": file["url"],
        })

    documented = documented_names()
    manifest_names = [entry["name"] for entry in entries]
    drift = []
    for name in sorted(set(documented) - set(manifest_names)):
        drift.append("documented but not installed: " + name)
    for name in sorted(set(manifest_names) - set(documented)):
        drift.append("installed but not documented: " + name)
    if drift:
        print("\nREADME and installed pack disagree:")
        for line in drift:
            print("  " + line)

    manifest = {
        "comment": "The test mod pack the two harnesses load into "
                   "targets/neoforge-1.21.1/run/mods. Regenerate with "
                   "scripts/jei-mod-env/build_pack_manifest.py from an installed pack; "
                   "download it on a fresh clone with scripts/jei-mod-env/download_pack.py.",
        "minecraft": "1.21.1",
        "loader": "neoforge",
        "files": entries,
    }
    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(manifest, handle, indent=1, ensure_ascii=False)
        handle.write("\n")
    total = sum(entry["size"] for entry in entries)
    print("wrote %s: %d entries, %.1f MiB"
          % (os.path.relpath(OUT, ROOT), len(entries), total / (1024.0 * 1024.0)))

    if unresolved:
        print("\nnot on Modrinth (download these by hand):")
        for name in unresolved:
            print("  " + name)
    if drift or unresolved:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
