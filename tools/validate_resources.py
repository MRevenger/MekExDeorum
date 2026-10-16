#!/usr/bin/env python3
"""Static resource checker for the MekExDeorum remake.

Runs from the repository root (``python tools/validate_resources.py``) and verifies
the packed asset/data tree plus the built jar against the frozen contract
(``_plan/SPEC_FREEZE.md`` sections 1, 3, 5, 6 and 8).

Exit code 0 = every check passed, 1 = at least one check failed.  Nothing is
modified; every check reads the working tree and the jar.

Requires Pillow (PNG dimensions) - everything else is stdlib.  TOML is read with
``tomllib``/``tomli`` when one of them is importable and with a targeted regex
parser otherwise, because this machine runs CPython 3.10.
"""

from __future__ import annotations

import json
import re
import sys
import zipfile
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover - environment problem, reported not raised
    Image = None

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "src" / "main" / "resources"
ASSETS = RESOURCES / "assets" / "mekexdeorum"
JAVA = ROOT / "src" / "main" / "java" / "com" / "mekexdeorum"
JAR_REL = "build/libs/mekexDeorum-1.0.0+mc1.21.1.jar"
JAR = ROOT / "build" / "libs" / "mekexDeorum-1.0.0+mc1.21.1.jar"

MOD_ID = "mekexdeorum"
BLOCK_IDS = [
    "universal_sieve",
    "basic_sieve_factory",
    "advanced_sieve_factory",
    "elite_sieve_factory",
    "ultimate_sieve_factory",
]
FACES = ["front", "back", "side", "top_outer", "top_inner", "bottom", "front_bar"]
MESH_ITEMS = [
    "exdeorum:string_mesh",
    "exdeorum:flint_mesh",
    "exdeorum:iron_mesh",
    "exdeorum:golden_mesh",
    "exdeorum:diamond_mesh",
    "exdeorum:netherite_mesh",
]
TAG_FILES = [
    "data/minecraft/tags/item/enchantable/mining.json",
    "data/minecraft/tags/item/enchantable/mining_loot.json",
    "data/minecraft/tags/block/mineable/pickaxe.json",
]
MODS_TOML = "META-INF/neoforge.mods.toml"

EXPECTED_TRANSLATION_KEYS = 14


# ----------------------------------------------------------------------------
# reporting
# ----------------------------------------------------------------------------

class Report:
    def __init__(self) -> None:
        self.results: list[tuple[str, bool, str, list[str]]] = []

    def add(self, name: str, ok: bool, summary: str, problems: list[str]) -> None:
        self.results.append((name, ok, summary, problems))

    def failed(self) -> bool:
        return any(not ok for _, ok, _, _ in self.results)

    def print(self) -> None:
        width = max(len(name) for name, _, _, _ in self.results)
        for name, ok, summary, problems in self.results:
            print(f"[{'PASS' if ok else 'FAIL'}] {name.ljust(width)}  {summary}")
            for problem in problems:
                print(f"         - {problem}")
        passed = sum(1 for _, ok, _, _ in self.results if ok)
        print()
        print(f"{passed}/{len(self.results)} checks passed")


# ----------------------------------------------------------------------------
# helpers
# ----------------------------------------------------------------------------

def load_json(path: Path):
    """Parses one JSON file, raising ValueError with the file name on failure."""
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise ValueError(f"{path.relative_to(ROOT)}: {exc}") from exc


def json_files_under(directory: Path) -> list[Path]:
    return sorted(p for p in directory.rglob("*.json") if p.is_file())


def qualify(key: str, default_ns: str = "minecraft") -> str:
    """Turns ``block/x`` / ``mekexdeorum:block/x`` into ``ns:path``."""
    return key if ":" in key else f"{default_ns}:{key}"


def split_key(key: str) -> tuple[str, str]:
    ns, _, path = qualify(key).partition(":")
    return ns, path


# ----------------------------------------------------------------------------
# check 1 - every JSON under src/main/resources parses
# ----------------------------------------------------------------------------

def check_json_parses(report: Report) -> None:
    problems: list[str] = []
    files = json_files_under(RESOURCES)
    for path in files:
        try:
            load_json(path)
        except ValueError as exc:
            problems.append(str(exc))
    report.add("1 JSON parses", not problems,
               f"{len(files) - len(problems)}/{len(files)} files under src/main/resources",
               problems)


# ----------------------------------------------------------------------------
# check 2 - the per-machine block textures (six faces + the P3 front bar) and the two icons
# ----------------------------------------------------------------------------

def check_textures(report: Report) -> None:
    problems: list[str] = []
    if Image is None:
        report.add("2 textures", False, "Pillow is not importable", ["pip install Pillow"])
        return

    checked = 0
    for block_id in BLOCK_IDS:
        for face in FACES:
            rel = f"textures/block/{block_id}_{face}.png"
            path = ASSETS / rel
            if not path.is_file():
                problems.append(f"missing assets/{MOD_ID}/{rel}")
                continue
            with Image.open(path) as image:
                if image.size != (16, 16):
                    problems.append(f"{rel} is {image.size[0]}x{image.size[1]}, expected 16x16")
                elif image.mode != "RGBA":
                    problems.append(f"{rel} is mode {image.mode}, expected RGBA")
                else:
                    checked += 1

    icons = 0
    for icon in (ROOT / "icon.png", RESOURCES / "icon.png"):
        if not icon.is_file():
            problems.append(f"missing {icon.relative_to(ROOT).as_posix()}")
            continue
        with Image.open(icon) as image:
            if image.size != (256, 256):
                problems.append(
                    f"{icon.relative_to(ROOT).as_posix()} is {image.size[0]}x{image.size[1]}, expected 256x256")
            elif image.mode != "RGBA":
                problems.append(f"{icon.relative_to(ROOT).as_posix()} is mode {image.mode}, expected RGBA")
            else:
                icons += 1

    report.add("2 textures", not problems,
               f"{checked}/{len(BLOCK_IDS) * len(FACES)} block textures are 16x16 RGBA, "
               f"{icons}/2 icons are 256x256 RGBA",
               problems)


# ----------------------------------------------------------------------------
# check 3 - every referenced texture (and model) resolves on disk
# ----------------------------------------------------------------------------

def model_index() -> dict[str, Path]:
    """ns:path -> model file, over every namespace shipped in this repository."""
    index: dict[str, Path] = {}
    for path in json_files_under(RESOURCES / "assets"):
        parts = path.relative_to(RESOURCES / "assets").parts
        if len(parts) < 4 or parts[1] != "models":
            continue
        ns = parts[0]
        rel = "/".join(parts[2:-1] + (path.stem,))
        index[f"{ns}:{rel}"] = path
    return index


def model_chain(start: str, index: dict[str, Path]) -> list[tuple[str, dict | None]]:
    """The parent chain of one model, root first; an unknown parent keeps its None entry."""
    chain: list[tuple[str, dict | None]] = []
    seen: set[str] = set()
    key = start
    while key not in seen:
        seen.add(key)
        path = index.get(key)
        if path is None:
            chain.append((key, None))
            break
        try:
            data = load_json(path)
        except ValueError:
            data = {}
        chain.append((key, data))
        parent = data.get("parent")
        if not parent:
            break
        key = qualify(parent)
    chain.reverse()
    return chain


def resolve_variable(name: str, merged: dict, depth: int = 0) -> str | None:
    if depth > 16 or name not in merged:
        return None
    value = merged[name]
    if isinstance(value, str) and value.startswith("#"):
        return resolve_variable(value[1:], merged, depth + 1)
    return value if isinstance(value, str) else None


def check_texture_refs(report: Report) -> None:
    problems: list[str] = []
    external_targets: set[str] = set()
    external_parents: set[str] = set()
    index = model_index()
    textures_root = RESOURCES / "assets" / MOD_ID / "textures"
    resolved: set[str] = set()

    def check_value(value: str, merged: dict, where: str) -> None:
        target = value
        if target.startswith("#"):
            target = resolve_variable(target[1:], merged)
            if target is None:
                problems.append(f"{where}: texture variable {value} does not resolve")
                return
        ns, path = split_key(target)
        if ns != MOD_ID:
            external_targets.add(f"{ns}:{path}")
            return
        png = textures_root / f"{path}.png"
        if png.is_file():
            resolved.add(path)
        else:
            problems.append(f"{where}: texture {target} -> assets/{MOD_ID}/textures/{path}.png is missing")

    def walk_model(start: str, origin: str) -> None:
        chain = model_chain(start, index)
        merged: dict = {}
        for key, data in chain:
            if isinstance(data, dict):
                merged.update(data.get("textures", {}))
            elif key not in index:
                external_parents.add(key)
        for key, data in chain:
            if not isinstance(data, dict):
                continue
            for name, value in data.get("textures", {}).items():
                if isinstance(value, str):
                    check_value(value, merged, f"{key} textures.{name} [{origin}]")
            for element in data.get("elements", []) or []:
                for face, spec in (element.get("faces") or {}).items():
                    value = (spec or {}).get("texture")
                    if isinstance(value, str):
                        check_value(value, merged, f"{key} faces.{face} [{origin}]")

    # blockstates -> model references
    for state_file in json_files_under(ASSETS / "blockstates"):
        data = load_json(state_file)
        for variant, spec in (data.get("variants") or {}).items():
            for entry in (spec if isinstance(spec, list) else [spec]):
                model = (entry or {}).get("model")
                if not isinstance(model, str):
                    problems.append(f"{state_file.name} variant {variant} has no model")
                    continue
                ns, path = split_key(model)
                if ns != MOD_ID:
                    external_parents.add(f"{ns}:{path} (blockstate)")
                    continue
                if f"{ns}:{path}" not in index:
                    problems.append(f"{state_file.name} variant {variant}: model {model} is missing")
                else:
                    walk_model(f"{ns}:{path}", state_file.name)

    # every model of the repository
    for key in sorted(index):
        walk_model(key, "model")

    report.add("3 texture refs resolve", not problems,
               f"{len(resolved)} distinct repo textures referenced and present; skipped "
               f"{len(external_targets)} out-of-namespace texture targets and "
               f"{len(external_parents)} vanilla/external models ({', '.join(sorted(external_parents)) or 'none'})",
               problems)


# ----------------------------------------------------------------------------
# check 4 - lang keys
# ----------------------------------------------------------------------------

def registered_block_names() -> list[str]:
    source = (JAVA / "registry" / "MekExDeorumBlocks.java").read_text(encoding="utf-8")
    names = re.findall(r'"([a-z][a-z0-9_]*)"', source)
    return sorted(set(names))


def java_translation_keys() -> list[str]:
    source = (JAVA / "MekExDeorumConfig.java").read_text(encoding="utf-8")
    return sorted(set(re.findall(r'\.translation\(\s*"(' + MOD_ID + r'\.configuration\.[^"]+)"', source)))


def check_lang(report: Report) -> None:
    problems: list[str] = []
    en = load_json(ASSETS / "lang" / "en_us.json")
    zh = load_json(ASSETS / "lang" / "zh_cn.json")
    en_keys, zh_keys = set(en), set(zh)

    for key in sorted(en_keys ^ zh_keys):
        problems.append(f"lang key {key} is in {'en_us' if key in en_keys else 'zh_cn'} only")

    config_keys = java_translation_keys()
    if len(config_keys) != EXPECTED_TRANSLATION_KEYS:
        problems.append(
            f"MekExDeorumConfig.java declares {len(config_keys)} mekexdeorum.configuration.* keys, "
            f"expected {EXPECTED_TRANSLATION_KEYS}")
    for key in config_keys:
        if key not in en_keys:
            problems.append(f"config translation key {key} is missing from the lang files")

    blocks = registered_block_names()
    lang_blocks = {k.rsplit(".", 1)[1] for k in en_keys if k.startswith(f"block.{MOD_ID}.")}
    for name in sorted(lang_blocks - set(blocks)):
        problems.append(f"lang key block.{MOD_ID}.{name} has no registered block")
    for name in sorted(set(blocks) - lang_blocks):
        problems.append(f"registered block {name} has no block.{MOD_ID}.{name} lang key")

    report.add("4 lang keys", not problems,
               f"{len(en_keys)} keys identical in en_us/zh_cn, {len(config_keys)} config keys covered, "
               f"{len(blocks)} block names matched",
               problems)


# ----------------------------------------------------------------------------
# check 5 - the mod metadata
# ----------------------------------------------------------------------------

def toml_tables(text: str) -> list[tuple[str, dict]]:
    """Targeted reader for the [[name]] tables of a mods.toml, in file order (names repeat)."""
    tables: list[tuple[str, dict]] = []
    current: dict | None = None
    for line in text.splitlines():
        stripped = line.strip()
        header = re.fullmatch(r"\[\[([^\]]+)\]\]", stripped)
        if header:
            current = {}
            tables.append((header.group(1), current))
            continue
        if current is None or stripped.startswith("#") or "=" not in stripped:
            continue
        key, _, value = stripped.partition("=")
        value = value.split("#")[0].strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        current.setdefault(key.strip(), value)
    return tables


def read_mods_toml() -> tuple[str, str]:
    """Returns (origin, text), preferring the generated copy inside the jar.

    The source template still carries Gradle ``${...}`` placeholders; they are expanded from
    gradle.properties so the metadata can also be checked before the first build.
    """
    if JAR.is_file():
        with zipfile.ZipFile(JAR) as archive:
            if MODS_TOML in archive.namelist():
                return f"jar:{JAR_REL}!", archive.read(MODS_TOML).decode("utf-8")
    source = RESOURCES / "META-INF" / "neoforge.mods.toml"
    text = source.read_text(encoding="utf-8")
    properties = {}
    props_file = ROOT / "gradle.properties"
    if props_file.is_file():
        for line in props_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, _, value = line.partition("=")
                properties[key.strip()] = value.strip()
    text = re.sub(r"\$\{([^}]+)\}", lambda m: properties.get(m.group(1), m.group(0)), text)
    return source.relative_to(ROOT).as_posix(), text


def check_mods_toml(report: Report) -> None:
    problems: list[str] = []
    origin, text = read_mods_toml()
    tables = toml_tables(text)

    mods = next((table for name, table in tables if name == "mods"), None)
    if not mods or mods.get("modId") != MOD_ID:
        problems.append(f"[[mods]].modId is {mods.get('modId') if mods else 'absent'}, expected {MOD_ID}")

    expected = {"mekanism": "[10.7.11,)", "exdeorum": "[3.12,)"}
    found: dict[str, str] = {}
    for name, table in tables:
        if name.startswith("dependencies") and table.get("modId") in expected:
            found[table["modId"]] = table.get("versionRange", "")
    for name, want in expected.items():
        got = found.get(name)
        if got != want:
            problems.append(f"dependency {name} versionRange is {got!r}, expected {want!r}")

    report.add("5 mods.toml", not problems,
               f"{origin}: modId {mods.get('modId') if mods else '?'}, "
               + ", ".join(f"{k} {found.get(k)!r}" for k in expected),
               problems)


# ----------------------------------------------------------------------------
# check 6 - the built jar
# ----------------------------------------------------------------------------

def expected_jar_entries() -> list[str]:
    entries = [
        "icon.png",
        "pack.mcmeta",
        MODS_TOML,
        "assets/mekexdeorum/lang/en_us.json",
        "assets/mekexdeorum/lang/zh_cn.json",
    ]
    entries += [f"assets/mekexdeorum/textures/block/{b}_{f}.png" for b in BLOCK_IDS for f in FACES]
    entries += [f"assets/mekexdeorum/blockstates/{b}.json" for b in BLOCK_IDS]
    entries += [f"assets/mekexdeorum/models/block/{name}.json"
                for name in sorted(p.stem for p in (ASSETS / "models" / "block").glob("*.json"))]
    entries += [f"assets/mekexdeorum/models/item/{b}.json" for b in BLOCK_IDS]
    entries += [f"data/mekexdeorum/loot_table/blocks/{b}.json" for b in BLOCK_IDS]
    entries += [f"data/mekexdeorum/recipe/{b}.json" for b in BLOCK_IDS]
    entries += TAG_FILES
    return entries


def check_jar(report: Report) -> None:
    problems: list[str] = []
    if not JAR.is_file():
        report.add("6 built jar", False, f"{JAR_REL} is missing - run build.ps1 first", problems)
        return

    extra_jars = [p.name for p in JAR.parent.glob("*.jar") if p.name != JAR.name]
    if extra_jars:
        problems.append(f"unexpected extra jars in build/libs: {', '.join(sorted(extra_jars))}")

    expected = expected_jar_entries()
    with zipfile.ZipFile(JAR) as archive:
        names = set(archive.namelist())
        if archive.testzip() is not None:
            problems.append(f"corrupt zip entry {archive.testzip()}")
    for entry in expected:
        if entry not in names:
            problems.append(f"jar is missing {entry}")

    report.add("6 built jar", not problems,
               f"{JAR_REL} ({JAR.stat().st_size} bytes) contains all {len(expected)} required entries, "
               f"{len(names)} entries total",
               problems)


# ----------------------------------------------------------------------------
# check 7 - the enchantment tags
# ----------------------------------------------------------------------------

def check_enchantment_tags(report: Report) -> None:
    problems: list[str] = []
    for rel in TAG_FILES[:2]:
        path = RESOURCES / rel
        if not path.is_file():
            problems.append(f"missing {rel}")
            continue
        data = load_json(path)
        if data.get("replace") is not False:
            problems.append(f"{rel}: replace is {data.get('replace')!r}, expected false")
        values = [v for v in data.get("values", []) if isinstance(v, str)]
        for mesh in MESH_ITEMS:
            if mesh not in values:
                problems.append(f"{rel}: {mesh} is not listed")
    report.add("7 enchantment tags", not problems,
               f"both item/enchantable tags list all {len(MESH_ITEMS)} exdeorum meshes with replace=false",
               problems)


# ----------------------------------------------------------------------------

def main() -> int:
    if not RESOURCES.is_dir():
        print(f"FAIL: {RESOURCES} does not exist; run this from the repository root")
        return 1

    report = Report()
    check_json_parses(report)
    check_textures(report)
    check_texture_refs(report)
    check_lang(report)
    check_mods_toml(report)
    check_jar(report)
    check_enchantment_tags(report)
    report.print()

    if report.failed():
        print("\nRESULT: FAILED")
        return 1
    print("\nRESULT: all resource checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
