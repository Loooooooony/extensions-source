"""Generate a Tachiyomi/Tachimanga-compatible extension repo from built src/ar APKs.

Usage: python .github/scripts/make_ar_repo.py <output_dir>

Reads each module's build/keiyoushi-source-info.json (emitted by assembleRelease),
collects the release APK and icon, and writes index.min.json / index.json plus
apk/ and icon/ folders into the output directory.
"""

import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "repo-out")

ICON_FILE = "res/mipmap-xxxhdpi/ic_launcher.png"
ICON_FALLBACKS = [
    "res/mipmap-xxxhdpi/ic_launcher.png",
    "res/mipmap-xxhdpi/ic_launcher.png",
    "res/mipmap-xhdpi/ic_launcher.png",
]


def find_icon(module: str, theme: str | None) -> Path | None:
    module_dir = ROOT / "src" / module.replace(".", "/")
    for rel in ICON_FALLBACKS:
        candidate = module_dir / rel
        if candidate.exists():
            return candidate
    if theme:
        theme_dir = ROOT / "lib-multisrc" / theme
        for rel in ICON_FALLBACKS:
            candidate = theme_dir / rel
            if candidate.exists():
                return candidate
    core_icon = ROOT / "core" / "src" / "main" / ICON_FILE
    return core_icon if core_icon.exists() else None


def main() -> None:
    apk_out = OUT / "apk"
    icon_out = OUT / "icon"
    apk_out.mkdir(parents=True, exist_ok=True)
    icon_out.mkdir(parents=True, exist_ok=True)

    entries = []
    skipped = []

    for info_file in sorted(ROOT.glob("src/ar/*/build/keiyoushi-source-info.json")):
        info = json.loads(info_file.read_text(encoding="utf-8"))
        pkg = info["packageName"]

        apks = list(info_file.parent.glob("outputs/apk/release/*.apk"))
        if not apks:
            skipped.append(pkg)
            continue
        apk = apks[0]

        shutil.copy2(apk, apk_out / apk.name)

        icon = find_icon(info["module"], info.get("theme"))
        if icon:
            shutil.copy2(icon, icon_out / f"{pkg}.png")

        entries.append({
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
                }
                for src in info["sources"]
            ],
        })

    (OUT / "index.min.json").write_text(
        json.dumps(entries, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    (OUT / "index.json").write_text(
        json.dumps(entries, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(f"Repo generated: {len(entries)} extensions -> {OUT}")
    if skipped:
        print("Skipped (no APK built):")
        for pkg in skipped:
            print(f"  - {pkg}")


if __name__ == "__main__":
    main()
