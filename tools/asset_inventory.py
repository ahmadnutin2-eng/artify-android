#!/usr/bin/env python3
"""
Artify asset provenance inventory (Phase 0A2).

Read-only: this script never modifies, moves or deletes an asset. It walks every bundled binary
asset, records facts that can be measured (hash, size, dimensions, embedded text metadata, which
brushes use it) and writes a CSV that a human completes with the provenance decision.

Usage (from the repository root):
    python3 tools/asset_inventory.py            # writes docs/provenance/asset-inventory.csv
    python3 tools/asset_inventory.py --check    # exit 1 if any asset lacks an approved decision

Requires Python 3.9+. Pillow is optional; without it image dimensions are left blank.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app" / "src" / "main"
ASSETS = MAIN / "assets"
RES = MAIN / "res"
JAVA = MAIN / "java"
OUT = ROOT / "docs" / "provenance" / "asset-inventory.csv"

BINARY_EXT = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".mp3", ".ogg", ".wav", ".ttf", ".otf"}
DATA_EXT = {".csv", ".json"}

# Decision columns are owned by the product owner. Existing decisions are preserved on re-run.
DECISION_COLUMNS = ["decision", "licence_or_source", "decided_by", "decided_on", "notes"]
ALLOWED_DECISIONS = {"original", "licensed", "public-domain", "replace", "remove-unused"}

BRUSH_RE = re.compile(r'Brush\(\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)"')
ASSET_REF_RE = re.compile(r'asset://brushes/([A-Za-z0-9_./-]+)')


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 16), b""):
            digest.update(block)
    return digest.hexdigest()


def image_facts(path: Path) -> tuple[str, str]:
    """Returns (WxH, embedded text metadata) for images, blank strings otherwise."""
    try:
        from PIL import Image  # type: ignore
    except ImportError:
        return "", ""
    try:
        with Image.open(path) as image:
            size = f"{image.width}x{image.height}"
            text = []
            for key, value in (image.info or {}).items():
                lowered = key.lower()
                if lowered in {"icc_profile", "exif", "transparency", "dpi", "gamma"}:
                    continue
                if not isinstance(value, (str, bytes)):
                    continue
                shown = value.decode("utf-8", "replace") if isinstance(value, bytes) else value
                if lowered in {"xmp", "xml:com.adobe.xmp"}:
                    # Keep only the authorship-relevant XMP fields, not the whole packet.
                    for field in ("xmp:CreatorTool", "dc:creator", "dc:rights", "photoshop:Credit",
                                  "xmpRights:UsageTerms", "xmpMM:DocumentID"):
                        match = re.search(rf'{field}[=>"\s]+([^"<]+)', shown)
                        if match:
                            text.append(f"{field}={match.group(1).strip()[:60]}")
                else:
                    text.append(f"{key}={shown[:80]}")
            exif = image.getexif() if hasattr(image, "getexif") else {}
            for tag in (0x013B, 0x8298, 0x0131):  # Artist, Copyright, Software
                if tag in exif:
                    text.append(f"exif{tag:#06x}={str(exif[tag])[:80]}")
            return size, "; ".join(text)
    except Exception as error:  # a corrupt asset is itself a finding
        return "unreadable", f"error={error}"


def brush_references() -> dict[str, list[str]]:
    """Maps 'textures/x.png' style paths to 'set:id (name)' labels, parsed from BrushLibrary."""
    refs: dict[str, list[str]] = defaultdict(list)
    for kt in JAVA.rglob("*.kt"):
        text = kt.read_text(encoding="utf-8", errors="replace")
        if "asset://brushes/" not in text:
            continue
        # Attribute each asset reference to the nearest preceding Brush( declaration.
        brushes = [(m.start(), m.group(1), m.group(2)) for m in BRUSH_RE.finditer(text)]
        for ref in ASSET_REF_RE.finditer(text):
            owner = None
            for start, brush_id, name in brushes:
                if start < ref.start():
                    owner = (brush_id, name)
                else:
                    break
            label = f"{owner[0]} ({owner[1]})" if owner else f"{kt.name}"
            if label not in refs[ref.group(1)]:
                refs[ref.group(1)].append(label)
    return refs


def metadata_raw_names() -> dict[str, list[str]]:
    """Maps texture/thumbnail file names to the RawName entries of brushes_metadata.json."""
    path = ASSETS / "brushes" / "brushes_metadata.json"
    names: dict[str, list[str]] = defaultdict(list)
    if not path.exists():
        return names
    for entry in json.loads(path.read_text(encoding="utf-8-sig")):
        for key in ("Shape", "Grain", "Thumbnail"):
            file_name = entry.get(key)
            if file_name:
                names[file_name].append(entry.get("RawName", "?"))
    return names


def code_references(relative_name: str) -> bool:
    needle = relative_name.split("/")[-1]
    for kt in JAVA.rglob("*.kt"):
        if needle in kt.read_text(encoding="utf-8", errors="replace"):
            return True
    return False


def load_existing_decisions() -> dict[str, dict[str, str]]:
    if not OUT.exists():
        return {}
    with OUT.open(newline="", encoding="utf-8") as handle:
        return {row["path"]: {c: row.get(c, "") for c in DECISION_COLUMNS} for row in csv.DictReader(handle)}


def evidence_flags(rel: str, raw_names: list[str], used_by: list[str]) -> str:
    flags = []
    if raw_names:
        flags.append("listed-in-extraction-metadata")
    if any(label.startswith("proc_") for label in used_by):
        flags.append("used-by-proc_-brush-id")
    if "/brushes/tx-arabic-" in f"/{rel}":
        flags.append("arabic-pack-shape-origin-unverified")
    if not used_by and rel.startswith("assets/brushes/") and not rel.endswith(".json"):
        flags.append("not-referenced-by-code")
    return "|".join(flags)


def collect() -> list[dict[str, str]]:
    refs = brush_references()
    raw = metadata_raw_names()
    existing = load_existing_decisions()
    rows = []
    candidates = [p for p in ASSETS.rglob("*") if p.is_file()]
    candidates += [p for p in RES.rglob("*") if p.is_file() and p.suffix.lower() in BINARY_EXT]
    for path in sorted(candidates):
        rel = path.relative_to(MAIN).as_posix()
        brush_key = rel.removeprefix("assets/brushes/")
        used_by = refs.get(brush_key, [])
        raw_names = raw.get(path.name, [])
        dims, embedded = image_facts(path) if path.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp"} else ("", "")
        row = {
            "path": rel,
            "kind": "binary" if path.suffix.lower() in BINARY_EXT else ("data" if path.suffix.lower() in DATA_EXT else "other"),
            "bytes": str(path.stat().st_size),
            "sha256": sha256(path),
            "dimensions": dims,
            "embedded_metadata": embedded,
            "used_by_brushes": " ; ".join(used_by),
            "referenced_in_code": "yes" if used_by or code_references(rel) else "no",
            "extraction_metadata_raw_names": " ; ".join(sorted(set(raw_names))),
            "evidence_flags": evidence_flags(rel, raw_names, used_by),
        }
        row.update(existing.get(rel, {c: "" for c in DECISION_COLUMNS}))
        rows.append(row)
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="fail if any asset lacks an allowed decision")
    args = parser.parse_args()

    rows = collect()
    if args.check:
        missing = [r["path"] for r in rows if r["decision"] not in ALLOWED_DECISIONS]
        blocked = [r["path"] for r in rows if r["decision"] in {"replace", "remove-unused"}]
        for path in missing:
            print(f"UNDECIDED  {path}")
        for path in blocked:
            print(f"BLOCKING   {path}")
        print(f"{len(rows)} assets, {len(missing)} undecided, {len(blocked)} still to replace/remove")
        return 1 if missing or blocked else 0

    OUT.parent.mkdir(parents=True, exist_ok=True)
    columns = list(rows[0].keys()) if rows else []
    with OUT.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    print(f"Wrote {len(rows)} rows to {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
