#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app" / "src" / "main"
RES = SRC / "res"
NS = "{http://schemas.android.com/apk/res/android}"

errors: list[str] = []
warnings: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


def warn(message: str) -> None:
    warnings.append(message)


def parse(path: Path):
    try:
        return ET.parse(path).getroot()
    except ET.ParseError as exc:
        fail(f"XML hatası: {path.relative_to(ROOT)}: {exc}")
        return None


strings: dict[str, list[Path]] = defaultdict(list)
colors: dict[str, list[Path]] = defaultdict(list)
styles: dict[str, list[Path]] = defaultdict(list)
layouts: set[str] = set()
drawables: set[str] = set()
ids: set[str] = set()

for xml_path in sorted(RES.rglob("*.xml")):
    root = parse(xml_path)
    if root is None:
        continue

    relative = xml_path.relative_to(ROOT)
    if xml_path.parent.name.startswith("layout"):
        layouts.add(xml_path.stem)
    if xml_path.parent.name.startswith("drawable"):
        drawables.add(xml_path.stem)

    if root.tag != "resources":
        continue

    for child in root:
        name = child.get("name")
        if not name:
            continue
        if child.tag == "string":
            strings[name].append(relative)
        elif child.tag == "color":
            colors[name].append(relative)
        elif child.tag == "style":
            styles[name].append(relative)

for name, files in strings.items():
    if len(files) > 1:
        fail(f"Yinelenen string: {name} -> {', '.join(str(p) for p in files)}")

for name, files in styles.items():
    if len(files) > 1:
        fail(f"Yinelenen style: {name} -> {', '.join(str(p) for p in files)}")

reference_pattern = re.compile(
    r"@(?:\+)?(string|color|style|layout|drawable|id)/([A-Za-z0-9_.]+)"
)

reference_files = sorted(RES.rglob("*.xml")) + [SRC / "AndroidManifest.xml"]
for xml_path in reference_files:
    root = parse(xml_path)
    if root is None:
        continue
    for element in root.iter():
        for value in element.attrib.values():
            for kind, name in reference_pattern.findall(value):
                if kind == "id":
                    ids.add(name)
                elif kind == "string":
                    if name not in strings:
                        fail(f"Eksik string: {name} ({xml_path.relative_to(ROOT)})")
                elif kind == "color":
                    if name not in colors:
                        fail(f"Eksik color: {name} ({xml_path.relative_to(ROOT)})")
                elif kind == "style":
                    if name not in styles:
                        fail(f"Eksik style: {name} ({xml_path.relative_to(ROOT)})")
                elif kind == "layout":
                    if name not in layouts:
                        fail(f"Eksik layout: {name} ({xml_path.relative_to(ROOT)})")
                elif kind == "drawable":
                    if name not in drawables:
                        fail(f"Eksik drawable: {name} ({xml_path.relative_to(ROOT)})")

kotlin_files = sorted((SRC / "java").rglob("*.kt"))
for kotlin_path in kotlin_files:
    text = kotlin_path.read_text(encoding="utf-8")
    pools = {
        "id": ids,
        "string": set(strings),
        "color": set(colors),
        "drawable": drawables,
        "layout": layouts,
        "style": set(styles),
    }
    for kind, pool in pools.items():
        for match in re.finditer(rf"R\.{kind}\.([A-Za-z0-9_]+)", text):
            name = match.group(1)
            if name not in pool:
                fail(f"Eksik R.{kind}.{name}: {kotlin_path.relative_to(ROOT)}")

manifest = parse(SRC / "AndroidManifest.xml")
if manifest is not None:
    permissions = {
        element.get(NS + "name")
        for element in manifest.findall("uses-permission")
    }
    if "android.permission.CAMERA" not in permissions:
        fail("Manifest'te CAMERA izni yok.")
    if "android.permission.INTERNET" in permissions:
        fail("Manifest'te INTERNET izni olmamalı (çevrimdışı uygulama).")

    features = {
        element.get(NS + "name"): element.get(NS + "required")
        for element in manifest.findall("uses-feature")
    }
    if features.get("android.hardware.camera.any") != "true":
        fail("android.hardware.camera.any uses-feature required=true olmalı.")

    application = manifest.find("application")
    if application is None:
        fail("Manifest'te application yok.")
    else:
        activities = {
            element.get(NS + "name")
            for element in application.findall("activity")
        }
        if ".MainActivity" not in activities:
            fail("Manifest'te .MainActivity kaydı yok.")

required_sources = [
    "MainActivity.kt",
    "FrameEnhancer.kt",
    "ZoomMath.kt",
]
for source in required_sources:
    if not (SRC / "java/com/wendigo/camerazoom" / source).exists():
        fail(f"{source} bulunamadı.")

gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
if 'versionName = "1.4"' not in gradle:
    fail("app/build.gradle.kts versionName 1.4 değil.")
if not re.search(r"versionCode\s*=\s*5\b", gradle):
    fail("app/build.gradle.kts versionCode 5 değil.")

workflow = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
if "testDebugUnitTest" not in workflow:
    fail("CI test adımı içermiyor.")
if "v1.4-build" not in workflow:
    fail("CI release etiketi v1.4-build değil.")

kotlin_text = "\n".join(path.read_text(encoding="utf-8") for path in kotlin_files)
for token in [
    "MAX_DIGITAL_FACTOR",
    "teleMode",
    "focusRing",
]:
    if token not in kotlin_text:
        warn(f"Beklenen geliştirme izi bulunamadı: {token}")

for message in warnings:
    print(f"UYARI: {message}")
for message in errors:
    print(f"HATA: {message}")

if errors:
    print(f"\n{len(errors)} hata bulundu.")
    sys.exit(1)

print("\nTamam: hata yok.")
