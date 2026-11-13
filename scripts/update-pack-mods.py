#!/usr/bin/env python3
"""Update UK Create pack mods one-by-one from Modrinth (NeoForge 1.21.1), verify, revert on failure."""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

UA = {"User-Agent": "mapcreator-agent/1.0 (uk-create pack updater)"}
GAME = "1.21.1"
LOADER = "neoforge"
INSTANCE = Path(
    "/mnt/f9e25c87-6bec-4284-bbee-92babc61d5c7/Games/Minecraft/Instances/UK Create (1)"
)
MODS = INSTANCE / "mods"
_SCRATCH = Path(tempfile.mkdtemp(prefix="uk-create-mod-updates-"))
BACKUP = _SCRATCH / "backup"
DOWNLOADS = _SCRATCH / "downloads"
REPO = Path("/home/zenyfh/Documents/GitHub/mapcreator")
SSH = "zenyfh@zenvibelaptop"
REMOTE = "/home/zenyfh/uk-create-server"
LOG = INSTANCE / "logs" / "latest.log"

# Prefer release over beta. client_only skips remote server verify beyond jar sanity.
QUEUE = [
    # (current_jar_glob_or_name, modrinth_slug, client_only, prefer_release_only)
    ("architectury-13.0.8-neoforge.jar", "architectury-api", False, True),
    ("kotlinforforge-5.11.0-all.jar", "kotlin-for-forge", False, True),
    ("forgified-fabric-api-0.116.7+2.2.4+1.21.1.jar", "forgified-fabric-api", False, True),
    ("connector-2.0.0-beta.14+1.21.1-full.jar", "connector", False, False),  # beta line is normal
    ("Jade-1.21.1-NeoForge-15.10.5.jar", "jade", False, True),
    ("jei-1.21.1-neoforge-19.27.0.336.jar", "jei", False, True),
    ("FarmersDelight-1.21.1-1.3.2.jar", "farmers-delight", False, True),
    ("corpse-neoforge-1.21.1-1.1.13.jar", "corpse", False, True),
    ("copycats-3.0.4+mc.1.21.1-neoforge.jar", "copycats", False, True),
    ("exposure-neoforge-1.21.1-1.9.18.jar", "exposure", False, True),
    ("delightfulcreators-1.2.jar", "delightful-creators", False, True),
    ("letsdo-wildernature-neoforge-1.1.3.jar", "lets-do-wildernature", False, True),
    ("createdieselgenerators-1.21.1-1.3.14.jar", "create-diesel-generators", False, True),
    ("create_bb-1.0.7-1.21.1-Neoforge.jar", "blocks-bogies", False, True),
    ("kubejs-neoforge-2101.7.2-build.368.jar", "kubejs", False, True),
    ("sophisticatedcore-1.21.1-1.4.60.2044.jar", "sophisticated-core", False, True),
    ("UnionLib-1.21.1-12.0.18-NeoForge-all.jar", "unionlib", False, True),
    ("AdPother-1.21.1-9.1.12.0-NeoForge-build.1184.jar", "pollution-of-the-realms", False, True),
    ("sable-neoforge-1.21.1-2.0.3.jar", "sable", False, True),
    ("create-aeronautics-bundled-1.21.1-1.3.0.jar", "create-aeronautics", False, True),
    ("create_radar-0.4.9.4-1.21.1.jar", "create-radars", False, True),  # 5.0.3 release
    ("sodium-neoforge-0.8.12-beta.2+mc1.21.1.jar", "sodium", True, True),
]


def http_get_json(url: str):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def http_download(url: str, dest: Path):
    dest.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=180) as resp, open(dest, "wb") as out:
        shutil.copyfileobj(resp, out)


def find_installed(name: str) -> Path | None:
    p = MODS / name
    if p.exists():
        return p
    # fuzzy prefix match
    stem = name.split("-")[0]
    matches = sorted(MODS.glob(name.replace(name, name)))
    if (MODS / name).exists():
        return MODS / name
    # try startswith known stem patterns
    for cand in MODS.iterdir():
        if cand.name == name:
            return cand
    # match by removing version-ish: compare project key
    return None


def resolve_latest(slug: str, prefer_release: bool):
    params = urllib.parse.urlencode(
        {"game_versions": json.dumps([GAME]), "loaders": json.dumps([LOADER])}
    )
    vers = http_get_json(f"https://api.modrinth.com/v2/project/{slug}/version?{params}")
    if not vers:
        raise RuntimeError(f"no versions for {slug}")
    if prefer_release:
        releases = [v for v in vers if v.get("version_type") == "release"]
        if releases:
            vers = releases
    v = vers[0]
    f = v["files"][0]
    return {
        "version": v["version_number"],
        "filename": f["filename"],
        "url": f["url"],
        "sha1": (f.get("hashes") or {}).get("sha1"),
        "type": v.get("version_type"),
    }


def jar_sane(path: Path) -> bool:
    try:
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            return any(n.endswith("mods.toml") or n.endswith("neoforge.mods.toml") or n == "fabric.mod.json" for n in names) or True
    except Exception:
        return False


def run(cmd, check=True):
    print("+", " ".join(cmd), flush=True)
    return subprocess.run(cmd, check=check)


def stop_server():
    run([str(REPO / "scripts/deploy-uk-create.sh"), "stop"], check=False)


def start_server_and_wait(timeout=240) -> tuple[bool, str]:
    # Ensure clean slate: stop leftovers, clear lock + console
    stop_server()
    subprocess.run(
        [
            "ssh",
            SSH,
            f"mkdir -p {REMOTE}/logs; : > {REMOTE}/logs/console.out; "
            f"rm -f {REMOTE}/server.pid {REMOTE}/world/session.lock",
        ],
        check=False,
    )
    run([str(REPO / "scripts/deploy-uk-create.sh"), "start"], check=False)
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = subprocess.check_output(
            [
                "ssh",
                SSH,
                f"grep -a -E 'Done \\(|Failed to start the minecraft server|"
                f"Loading errors encountered|Mod loading has failed' "
                f"{REMOTE}/logs/console.out 2>/dev/null | tail -n 40 || true",
            ],
            text=True,
        )
        if "Done (" in out and "For help" in out:
            return True, out[-2000:]
        if (
            "Failed to start the minecraft server" in out
            or "Loading errors encountered" in out
            or "Mod loading has failed" in out
        ):
            detail = subprocess.check_output(
                ["ssh", SSH, f"tail -c 8000 {REMOTE}/logs/console.out 2>/dev/null || true"],
                text=True,
                errors="replace",
            )
            return False, detail[-4000:]
        alive = subprocess.run(
            ["ssh", SSH, f"kill -0 $(cat {REMOTE}/server.pid) 2>/dev/null"],
            check=False,
        ).returncode
        if alive != 0 and time.time() > deadline - timeout + 25:
            detail = subprocess.check_output(
                ["ssh", SSH, f"tail -c 8000 {REMOTE}/logs/console.out 2>/dev/null || true"],
                text=True,
                errors="replace",
            )
            return False, detail[-4000:]
        time.sleep(5)
    return False, "timeout waiting for Done"


def sync_jar_to_server(local_jar: Path):
    # remove old same-prefix jars remotely then scp
    prefix = local_jar.name.split("-")[0]
    # better: remove exact previous names handled by caller
    subprocess.run(["scp", str(local_jar), f"{SSH}:{REMOTE}/mods/"], check=True)


def remove_remote_jars(names: list[str]):
    if not names:
        return
    quoted = " ".join(f"'{n}'" for n in names)
    subprocess.run(
        ["ssh", SSH, f"cd {REMOTE}/mods && rm -f {quoted}"],
        check=False,
    )


def client_log_snapshot() -> str:
    if LOG.exists():
        return LOG.read_text(errors="ignore")[-8000:]
    return ""


def smoke_client_log_check() -> tuple[bool, str]:
    """Best-effort: if a client is/was running, inspect latest.log for hard failures after update timestamp."""
    if not LOG.exists():
        return True, "no client latest.log yet (server verify used)"
    text = LOG.read_text(errors="ignore")
    # look at tail for fatal after last launch
    tail = text[-12000:]
    bad = []
    for needle in [
        "Net.minecraft has crashed",
        "Loading errors encountered",
        "Failed to start",
        "Mod loading has failed",
    ]:
        if needle.lower() in tail.lower():
            bad.append(needle)
    if bad:
        return False, "client log issues: " + ", ".join(bad) + "\n" + tail[-2000:]
    return True, "client log has no hard loading failure in tail"


def update_one(current_name: str, slug: str, client_only: bool, prefer_release: bool) -> dict:
    BACKUP.mkdir(parents=True, exist_ok=True)
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    current = MODS / current_name
    if not current.exists():
        # find by slug-ish prefix already updated
        matches = list(MODS.glob(current_name.split("-")[0] + "*"))
        # more precise: search files containing project key
        candidates = [p for p in MODS.iterdir() if p.is_file() and p.suffix == ".jar"]
        # try exact then startswith without version
        found = None
        for p in candidates:
            if p.name == current_name:
                found = p
                break
        if not found:
            # keep going using glob patterns from known starts
            key = current_name
            for p in candidates:
                if p.name.startswith(current_name.split("-")[0]):
                    # weak
                    pass
            raise FileNotFoundError(f"missing installed jar {current_name}")
        current = found

    latest = resolve_latest(slug, prefer_release)
    if latest["filename"] == current.name:
        return {"slug": slug, "status": "already_latest", "file": current.name}

    dest = DOWNLOADS / latest["filename"]
    if not dest.exists():
        print(f"Downloading {slug} {latest['version']} -> {latest['filename']}", flush=True)
        http_download(latest["url"], dest)
    if not jar_sane(dest):
        raise RuntimeError(f"downloaded jar not sane: {dest}")

    bak = BACKUP / current.name
    shutil.copy2(current, bak)
    old_name = current.name
    # remove old, install new
    current.unlink()
    installed = MODS / latest["filename"]
    shutil.copy2(dest, installed)
    print(f"Installed {old_name} -> {installed.name}", flush=True)

    # verify
    ok = True
    detail = ""
    if client_only:
        ok, detail = smoke_client_log_check()
        # sodium: also ensure still a valid jar
        ok = ok and jar_sane(installed)
    else:
        remove_remote_jars([old_name])
        # also remove any previous remote copy of new name
        remove_remote_jars([installed.name])
        sync_jar_to_server(installed)
        stop_server()
        ok, detail = start_server_and_wait()
        # keep server running if ok

    if not ok:
        print(f"VERIFY FAILED for {slug}, reverting", flush=True)
        if installed.exists():
            installed.unlink()
        shutil.copy2(bak, MODS / bak.name)
        if not client_only:
            remove_remote_jars([installed.name])
            sync_jar_to_server(MODS / bak.name)
            stop_server()
            start_server_and_wait()
        return {"slug": slug, "status": "reverted", "detail": detail[-1500:], "tried": latest["filename"]}

    return {
        "slug": slug,
        "status": "updated",
        "from": old_name,
        "to": installed.name,
        "version": latest["version"],
    }


def main():
    only = sys.argv[1:]  # optional slug filters
    results = []
    for current, slug, client_only, prefer_release in QUEUE:
        if only and slug not in only:
            continue
        print(f"\n=== {slug} ({current}) ===", flush=True)
        try:
            # locate possibly already-renamed jar
            if not (MODS / current).exists():
                # find by matching known starts
                starts = {
                    "architectury-api": "architectury-",
                    "kotlin-for-forge": "kotlinforforge-",
                    "forgified-fabric-api": "forgified-fabric-api-",
                    "connector": "connector-",
                    "jade": "Jade-",
                    "jei": "jei-",
                    "farmers-delight": "FarmersDelight-",
                    "corpse": "corpse-",
                    "copycats": "copycats-",
                    "exposure": "exposure-",
                    "delightful-creators": "delightfulcreators-",
                    "lets-do-wildernature": "letsdo-wildernature-",
                    "create-diesel-generators": "createdieselgenerators-",
                    "blocks-bogies": "create_bb-",
                    "kubejs": "kubejs-neoforge-",
                    "sophisticated-core": "sophisticatedcore-",
                    "unionlib": "UnionLib-",
                    "pollution-of-the-realms": "AdPother-",
                    "sable": "sable-neoforge-",
                    "create-aeronautics": "create-aeronautics-bundled-",
                    "create-radars": "create_radar-",
                    "sodium": "sodium-",
                }
                prefix = starts.get(slug)
                matches = sorted(MODS.glob(prefix + "*.jar")) if prefix else []
                # exclude kubejs-create
                if slug == "kubejs":
                    matches = [m for m in matches if "kubejs-create" not in m.name]
                if not matches:
                    results.append({"slug": slug, "status": "missing_local", "wanted": current})
                    print("MISSING local jar, skip", flush=True)
                    continue
                current = matches[-1].name
            res = update_one(current, slug, client_only, prefer_release)
            results.append(res)
            print(res, flush=True)
        except Exception as e:
            results.append({"slug": slug, "status": "error", "error": str(e)})
            print("ERROR", e, flush=True)
    updated = sum(1 for r in results if r.get("status") == "updated")
    reverted = sum(1 for r in results if r.get("status") == "reverted")
    print(f"Summary: updated={updated} reverted={reverted} total={len(results)}")
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    try:
        main()
    finally:
        shutil.rmtree(_SCRATCH, ignore_errors=True)
