#!/usr/bin/env python3
"""Static checks that need no Android SDK:
  - no emoji or pictograms in any source, resource, script or document (arrows and ordinary punctuation are fine);
  - every XML file is well formed;
  - every @type/name reference in the manifest and resources points at something that exists;
  - string resources follow aapt2's rules (escaped apostrophes, no stray format markers);
  - shell scripts parse (bash -n), Python scripts compile, workflow YAML parses.
Usage: tools/check-static.py            (run from anywhere)
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SKIP_DIRS = {".git", "build", ".gradle", "node_modules", "__pycache__"}
TEXT_EXT = {".kt", ".kts", ".xml", ".js", ".sh", ".py", ".md", ".yml", ".yaml", ".json", ".env", ".properties", ".pro", ".gradle", ".txt"}
problems = []


def files():
    for base, dirs, names in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for n in names:
            path = os.path.join(base, n)
            ext = os.path.splitext(n)[1]
            if ext in TEXT_EXT or n in ("Dockerfile", "AGENTS.md", "gradlew") or "rootfs-overlay" in path:
                yield path


def rel(p):
    return os.path.relpath(p, ROOT)


# Ranges that hold emoji and pictographs, built from code points so this file holds none itself. Arrows used by the keys
# row, the copyright sign and similar plain symbols are deliberately not on the list.
RANGES = [
    (0x1F000, 0x1FAFF),  # emoticons, symbols, flags, transport, supplemental
    (0x2600, 0x27BF),  # miscellaneous symbols and dingbats
    (0x2B00, 0x2BFF),  # arrows and stars that render as emoji
    (0x2300, 0x23FF),  # technical symbols (watch, hourglass, ...)
    (0xFE0F, 0xFE0F),  # emoji variation selector
    (0x200D, 0x200D),  # zero width joiner
    (0x20E3, 0x20E3),  # keycap
    (0x2194, 0x2199),  # diagonal and two-way arrows
    (0x21A9, 0x21AA),
    (0x2934, 0x2935),
    (0x203C, 0x203C),
    (0x2049, 0x2049),
    (0x2139, 0x2139),
    (0x25AA, 0x25AB),
    (0x25B6, 0x25B6),
    (0x25C0, 0x25C0),
    (0x25FB, 0x25FE),
    (0x3030, 0x3030),
    (0x303D, 0x303D),
    (0x3297, 0x3299),
]


def is_emoji(ch):
    code = ord(ch)
    return any(lo <= code <= hi for lo, hi in RANGES)


for path in files():
    try:
        text = open(path, encoding="utf-8").read()
    except (UnicodeDecodeError, OSError):
        continue
    for lineno, line in enumerate(text.splitlines(), 1):
        hit = next((ch for ch in line if is_emoji(ch)), None)
        if hit is not None:
            problems.append(f"{rel(path)}:{lineno}: emoji or pictograph U+{ord(hit):04X}")

# XML
xml_files = [p for p in files() if p.endswith(".xml")]
trees = {}
for path in xml_files:
    try:
        trees[path] = ET.parse(path)
    except ET.ParseError as e:
        problems.append(f"{rel(path)}: not well-formed XML ({e})")

res = os.path.join(ROOT, "android/app/src/main/res")
defined = {}  # type -> set(name)


def define(kind, name):
    defined.setdefault(kind, set()).add(name.replace(".", "_"))


for entry in os.listdir(res):
    folder = os.path.join(res, entry)
    if not os.path.isdir(folder):
        continue
    kind = entry.split("-")[0]
    for f in os.listdir(folder):
        path = os.path.join(folder, f)
        if kind == "values":
            tree = trees.get(path)
            if tree is None:
                continue
            for el in tree.getroot():
                name = el.get("name")
                if name and el.tag in ("string", "color", "dimen", "style", "bool", "integer", "array", "plurals"):
                    define(el.tag, name)
                    if el.tag == "style":
                        pass
        else:
            define(kind, os.path.splitext(f)[0])

ref = re.compile(r"@(?:\+id/|android:)?(drawable|color|string|style|xml|mipmap|dimen|bool|integer|array|layout)/([A-Za-z0-9_.]+)")
for path, tree in trees.items():
    if "/res/" not in path and not path.endswith("AndroidManifest.xml"):
        continue
    text = open(path, encoding="utf-8").read()
    for m in ref.finditer(text):
        before = text[max(0, m.start() - 9):m.start() + 1]
        if text[m.start():m.start() + 9] == "@android:" or "@android:" in text[m.start():m.end()]:
            continue
        kind, name = m.group(1), m.group(2).replace(".", "_")
        if name not in defined.get(kind, set()):
            problems.append(f"{rel(path)}: reference @{kind}/{m.group(2)} does not exist")

# String resources
for path in xml_files:
    if not re.search(r"/res/values[^/]*/strings\.xml$", path) or path not in trees:
        continue
    for el in trees[path].getroot():
        if el.tag != "string":
            continue
        text = "".join(el.itertext())
        raw = el.text or ""
        quoted = raw.startswith('"') and raw.endswith('"')
        if not quoted and re.search(r"(?<!\\)'", raw):
            problems.append(f"{rel(path)}: string '{el.get('name')}' has an unescaped apostrophe (aapt2 refuses it)")
        if raw.startswith("@") or raw.startswith("?"):
            problems.append(f"{rel(path)}: string '{el.get('name')}' starts with @ or ?")
        markers = re.findall(r"%(?!%)(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]", text)
        if len(markers) > 1 and any("$" not in m for m in markers):
            problems.append(f"{rel(path)}: string '{el.get('name')}' has several format markers without positions")

# Scripts and workflows
for path in files():
    if path.endswith(".sh") or "rootfs-overlay/usr/local/bin" in path:
        head = open(path, encoding="utf-8", errors="replace").readline()
        shell = "sh" if head.startswith("#!/bin/sh") else "bash"
        r = subprocess.run([shell, "-n", path], capture_output=True, text=True)
        if r.returncode != 0:
            problems.append(f"{rel(path)}: shell syntax error: {r.stderr.strip().splitlines()[-1] if r.stderr else ''}")
        if not os.access(path, os.X_OK) and "/lib/" not in path and ("rootfs-overlay/usr/local/bin" in path or ("/runtime/" in path and path.endswith(".sh"))):
            problems.append(f"{rel(path)}: not executable")
    elif path.endswith(".py"):
        try:
            compile(open(path, encoding="utf-8").read(), path, "exec")
        except SyntaxError as e:
            problems.append(f"{rel(path)}: {e}")
    elif path.endswith((".yml", ".yaml")):
        try:
            import yaml  # type: ignore
        except ImportError:
            continue
        try:
            yaml.safe_load(open(path, encoding="utf-8"))
        except Exception as e:  # noqa: BLE001
            problems.append(f"{rel(path)}: YAML error {e}")
    elif path.endswith(".json"):
        import json
        try:
            json.load(open(path, encoding="utf-8"))
        except ValueError as e:
            problems.append(f"{rel(path)}: JSON error {e}")

# Workflow wiring: every steps.* and needs.* reference must point at something that exists
try:
    import yaml  # type: ignore
except ImportError:
    yaml = None

if yaml is not None:
    wf_dir = os.path.join(ROOT, ".github", "workflows")
    for name in sorted(os.listdir(wf_dir)) if os.path.isdir(wf_dir) else []:
        path = os.path.join(wf_dir, name)
        doc = yaml.safe_load(open(path, encoding="utf-8"))
        jobs = doc.get("jobs", {})
        for job_id, job in jobs.items():
            text = yaml.safe_dump(job)
            needs = job.get("needs", [])
            needs = [needs] if isinstance(needs, str) else list(needs)
            step_ids = {st.get("id") for st in job.get("steps", []) if st.get("id")}
            for sid in set(re.findall(r"steps\.([A-Za-z0-9_-]+)\.outputs", text)):
                if sid not in step_ids:
                    problems.append(f"{rel(path)}: job {job_id} reads steps.{sid} but has no such step id")
            for dep, out in set(re.findall(r"needs\.([A-Za-z0-9_-]+)\.outputs\.([A-Za-z0-9_]+)", text)):
                if dep not in needs:
                    problems.append(f"{rel(path)}: job {job_id} reads needs.{dep} but does not list it in needs")
                elif out not in (jobs.get(dep, {}).get("outputs") or {}):
                    problems.append(f"{rel(path)}: job {job_id} reads needs.{dep}.outputs.{out}, which job {dep} does not declare")
            for dep in needs:
                if dep not in jobs:
                    problems.append(f"{rel(path)}: job {job_id} needs unknown job {dep}")
            for st in job.get("steps", []):
                uses = st.get("uses", "")
                if uses and not uses.startswith("./") and not re.search(r"@[0-9a-f]{40}$", uses):
                    problems.append(f"{rel(path)}: job {job_id} uses {uses}, which is not pinned to a commit")

if problems:
    print("\n".join(problems))
    print(f"{len(problems)} problem(s)")
    sys.exit(1)

print("static checks passed")
