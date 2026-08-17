"""Generate a Tachimanga-compatible extension repo from built src/ar APKs.

Usage: python .github/scripts/make_ar_repo.py <output_dir>

Outputs into <output_dir>:
  - index.pb          modern protobuf index (used by current Tachimanga/Mihon)
  - index.json        protobuf-JSON mirror of the index
  - index.min.json    legacy JSON index (older clients)
  - apk/  jar/  icon/

Set GITHUB_REPOSITORY (owner/repo) so absolute URLs in index.pb are correct.
"""

import json
import shutil
import sys
import os
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import index_pb2  # noqa: E402
from google.protobuf import json_format  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "repo-out")

GITHUB_REPO = os.environ.get("GITHUB_REPOSITORY", "Loooooooony/extensions-source")
BASE_URL = f"https://raw.githubusercontent.com/{GITHUB_REPO}/repo"

ICON_FALLBACKS = [
    "res/mipmap-xxxhdpi/ic_launcher.png",
    "res/mipmap-xxhdpi/ic_launcher.png",
    "res/mipmap-xhdpi/ic_launcher.png",
]


def find_icon(module: str, theme: str | None) -> Path | None:
    module_dir = ROOT / "src" / module.replace(".", "/")
    candidates = [module_dir] + ([ROOT / "lib-multisrc" / theme] if theme else []) + [ROOT / "core" / "src" / "main"]
    for base in candidates:
        for rel in ICON_FALLBACKS:
            if (base / rel).exists():
                return base / rel
    return None


def main() -> None:
    apk_out, jar_out, icon_out = OUT / "apk", OUT / "jar", OUT / "icon"
    for d in (apk_out, jar_out, icon_out):
        d.mkdir(parents=True, exist_ok=True)

    index = index_pb2.Index()
    index.name = "Loooooooony Arabic Extensions"
    index.badgeLabel = "Discord"
    index.signingKey = "7d44ab0fdfa10a528d0b43c64376201bb0a4fce27e7effb85d88afad868deb08"
    index.contact.website = f"https://github.com/{GITHUB_REPO}"
    ext_list = index.extensionList.extensions

    legacy_entries = []
    skipped = []

    for info_file in sorted(ROOT.glob("src/ar/*/build/keiyoushi-source-info.json")):
        info = json.loads(info_file.read_text(encoding="utf-8"))
        pkg = info["packageName"]

        apks = list(info_file.parent.glob("outputs/apk/release/*.apk"))
        jars = list(info_file.parent.glob("outputs/jar/release/*.jar"))
        if not apks:
            skipped.append(pkg)
            continue
        apk = apks[0]

        shutil.copy2(apk, apk_out / apk.name)
        jar_name = ""
        if jars:
            shutil.copy2(jars[0], jar_out / jars[0].name)
            jar_name = jars[0].name

        icon = find_icon(info["module"], info.get("theme"))
        icon_name = ""
        if icon:
            icon_name = f"{pkg}.png"
            shutil.copy2(icon, icon_out / icon_name)

        ext = ext_list.add()
        ext.name = info["name"]
        ext.packageName = pkg
        ext.resources.apkUrl = f"{BASE_URL}/apk/{apk.name}"
        if icon_name:
            ext.resources.iconUrl = f"{BASE_URL}/icon/{icon_name}"
        if jar_name:
            ext.resources.jarUrl = f"{BASE_URL}/jar/{jar_name}"
        ext.extensionLib = info["extensionLib"]
        ext.versionCode = info["versionCode"]
        ext.versionName = info["versionName"]
        ext.contentWarning = info.get("contentWarning", 0)
        for src in info["sources"]:
            s = ext.sources.add()
            s.id = src["id"]
            s.name = src["name"]
            s.language = src["lang"]
            s.homeUrl = src["baseUrl"]
            s.mirrorUrls.extend(src.get("mirrorUrls", []))

        legacy_entries.append({
            "name": info["name"],
            "pkg": pkg,
            "apk": apk.name,
            "lang": "ar",
            "code": info["versionCode"],
            "version": info["versionName"],
            "nsfw": 1 if info.get("contentWarning", 0) >= 3 else 0,
            "sources": [
                {
                    "name": src["name"],
                    "lang": src["lang"],
                    "id": str(src["id"]),
                    "baseUrl": src["baseUrl"],
                    "versionId": 1,
                }
                for src in info["sources"]
            ],
        })

    (OUT / "index.pb").write_bytes(index.SerializeToString())
    (OUT / "index.json").write_text(
        json_format.MessageToJson(index, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (OUT / "index.min.json").write_text(
        json.dumps(legacy_entries, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )

    print(f"Repo generated: {len(legacy_entries)} extensions -> {OUT}")
    if skipped:
        print("Skipped (no APK built):")
        for pkg in skipped:
            print(f"  - {pkg}")


if __name__ == "__main__":
    main()
