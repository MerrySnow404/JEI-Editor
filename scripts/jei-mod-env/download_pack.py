"""Download the test mod pack on a fresh clone.

`targets/neoforge-1.21.1/run/mods` is gitignored, so a fresh clone has no pack and
the two harnesses cannot run. This installs it from `pack-manifest.json`, the
tracked list of the pack's 76 files (68 active + 8 disabled) with the Modrinth
version each one came from and its sha1:

    python scripts/jei-mod-env/download_pack.py

Only missing files are fetched; an installed file is re-hashed and left alone when
its sha1 matches the manifest, so re-running is cheap and cannot silently replace
a jar the manifest disagrees with (use --force for that, deliberately). Afterwards
run `python scripts/jei-mod-env/verify.py`: it should print `jars: 68`, and it
always reports `jei` as a missing required dependency - JEI is not part of the
pack, the run classpath provides it - so its non-zero exit code is expected here.
"""
import argparse
import hashlib
import json
import os
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MANIFEST = os.path.join(ROOT, "scripts", "jei-mod-env", "pack-manifest.json")
MODS = os.path.join(ROOT, "targets", "neoforge-1.21.1", "run", "mods")
UA = {"User-Agent": "jei-editor-research/1.0"}
ATTEMPTS = 3


def load_manifest():
    with open(MANIFEST, encoding="utf-8") as handle:
        return json.load(handle)


def sha1(path):
    digest = hashlib.sha1()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch(url, destination):
    """Download one file, retrying a transient failure."""
    last = None
    for attempt in range(1, ATTEMPTS + 1):
        try:
            request = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(request, timeout=120) as response, \
                    open(destination, "wb") as handle:
                while True:
                    chunk = response.read(1 << 20)
                    if not chunk:
                        break
                    handle.write(chunk)
            return True
        except Exception as exception:      # network, TLS, disk - all retryable here
            last = exception
            if attempt < ATTEMPTS:
                time.sleep(2 * attempt)
    raise RuntimeError(str(last))


def install(entry, args):
    """Fetch one manifest entry; returns (status, message)."""
    name = entry["name"]
    target = os.path.join(MODS, name)
    if entry.get("url") is None:
        return "failed", "no download url in the manifest (not on Modrinth)"
    if os.path.exists(target):
        if sha1(target) == entry["sha1"]:
            return "present", None
        if not args.force:
            return "failed", "installed file differs from the manifest (use --force)"
    if args.dry_run:
        return "missing", None
    partial = target + ".part"
    try:
        fetch(entry["url"], partial)
        digest = sha1(partial)
        if digest != entry["sha1"]:
            return "failed", "sha1 mismatch after download: %s" % digest
        if os.path.getsize(partial) != entry["size"]:
            return "failed", "size mismatch after download"
        os.replace(partial, target)
        return "downloaded", None
    except Exception as exception:
        return "failed", str(exception)
    finally:
        if os.path.exists(partial):
            os.remove(partial)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true",
                        help="report what is missing without downloading anything")
    parser.add_argument("--force", action="store_true",
                        help="replace an installed file whose sha1 differs from the manifest")
    parser.add_argument("--jobs", type=int, default=4,
                        help="parallel downloads (default 4)")
    args = parser.parse_args()

    manifest = load_manifest()
    entries = manifest["files"]
    os.makedirs(MODS, exist_ok=True)
    print("pack: %s (%s / %s), %d files, %.1f MiB"
          % (os.path.relpath(MANIFEST, ROOT), manifest["loader"], manifest["minecraft"],
             len(entries), sum(e["size"] for e in entries) / (1024.0 * 1024.0)))
    print("into: %s" % os.path.relpath(MODS, ROOT))

    results = []
    with ThreadPoolExecutor(max_workers=max(1, args.jobs)) as pool:
        futures = [pool.submit(install, entry, args) for entry in entries]
        for entry, future in zip(entries, futures):
            status, message = future.result()
            results.append((status, entry["name"], message))
            if status == "downloaded":
                print("  downloaded  %s" % entry["name"])
            elif status == "failed":
                print("  FAILED      %s: %s" % (entry["name"], message))

    counts = {}
    for status, _, _ in results:
        counts[status] = counts.get(status, 0) + 1
    print("\n%s" % ", ".join("%s %d" % (k, counts[k]) for k in sorted(counts)))
    if args.dry_run and counts.get("missing"):
        print("re-run without --dry-run to install them")
    if counts.get("failed"):
        print("something is missing; the failures above are listed by name")
        return 1
    print("\nnext: python scripts/jei-mod-env/verify.py "
          "(expect 'jars: 68'; it reports jei as missing, which is expected)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
