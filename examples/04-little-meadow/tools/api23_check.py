#!/usr/bin/env python3
"""
Finds framework calls the frame (Android 6, API 23) doesn't have.

Android Lint's NewApi check reports nothing with this Kotlin/AGP combination, and two API 26/29
calls crashed on the device. This script checks the compiled app classes directly: every method or
field they reference on android.*, java.*, javax.*, dalvik.*, org.json.* must exist in API 23's
android.jar (in the class itself or a superclass/interface).

    python3 -I tools/api23_check.py            # after ./gradlew assembleDebug
Exit code 1 if anything is missing. Needs `javap` (JDK) and platforms;android-23.
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SDK = Path(os.environ.get("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools"))
JAR = SDK / "platforms/android-23/android.jar"
# AGP 9 built-in Kotlin writes classes here (older AGP: app/build/tmp/kotlin-classes/debug)
CLASS_DIRS = [ROOT / "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes", ROOT / "app/build/tmp/kotlin-classes/debug"]
# Java 8 statics that D8 rewrites ("backports") for minSdk < 24, so they never reach the device as-is.
# Kotlin data classes call e.g. Long.hashCode(long) in their generated hashCode().
BACKPORTED_OWNERS = {f"java/lang/{t}" for t in ("Boolean", "Byte", "Short", "Character", "Integer", "Long", "Float", "Double")} | {"java/util/Objects", "java/lang/Math", "java/lang/StrictMath"}
BACKPORTED_NAMES = {"hashCode", "compare", "sum", "max", "min", "toUnsignedString", "parseUnsignedInt", "parseUnsignedLong",
                    "divideUnsigned", "remainderUnsigned", "compareUnsigned", "toUnsignedLong", "logicalAnd", "logicalOr", "logicalXor",
                    "isFinite", "equals", "requireNonNull", "isNull", "nonNull", "toString", "requireNonNullElse",
                    "addExact", "subtractExact", "multiplyExact", "negateExact", "incrementExact", "decrementExact",
                    "toIntExact", "floorDiv", "floorMod", "nextDown", "fma"}
PLATFORM = ("android/", "java/", "javax/", "dalvik/", "org/json/", "org/xmlpull/", "org/w3c/", "org/xml/")

REF = re.compile(r"// (Method|InterfaceMethod|Field) ([\w/$]+)\.(\"?[\w<>$]+\"?):(\S+)")
HEADER = re.compile(r"^(?:[\w ]*?)(?:class|interface) ([\w.$]+)(?:<[^{]*?>)?(?: extends ([\w.$, <>?]+?))?(?: implements ([\w.$, <>?]+?))? ?\{$")
MEMBER = re.compile(r"^  .*?([\w$<>]+)(?:\(.*\))?(?: throws .*)?;$")


def javap(args):
    return subprocess.run(["javap", *args], capture_output=True, text=True).stdout


def app_references():
    files = [str(p) for d in CLASS_DIRS if d.exists() for p in d.rglob("*.class")]
    refs = {}
    for i in range(0, len(files), 200):
        current = None
        for line in javap(["-c", "-p", *files[i:i + 200]]).splitlines():
            if line.startswith("Compiled from") or HEADER.match(line):
                m = HEADER.match(line)
                if m:
                    current = m.group(1)
                continue
            m = REF.search(line)
            if m and m.group(2).startswith(PLATFORM):
                kind, owner, name, desc = m.groups()
                refs.setdefault((owner, name.strip('"'), desc, kind == "Field"), set()).add(current)
    return refs


class Api:
    """Members of API 23 classes, loaded lazily with javap -s."""

    def __init__(self):
        self.members = {}  # owner -> set of (name, desc)
        self.supers = {}   # owner -> [super/interfaces]
        self.jar = zipfile.ZipFile(JAR)
        self.tmp = Path(tempfile.mkdtemp(prefix="api23-"))

    def load(self, owners):
        todo = [o for o in owners if o not in self.members]
        for i in range(0, len(todo), 100):
            batch = todo[i:i + 100]
            # javap -cp would read java.* from the JDK itself, not from android.jar: extract and pass file paths
            paths = []
            for o in batch:
                entry = o + ".class"
                if entry in self.jar.namelist():
                    target = self.tmp / entry
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(self.jar.read(entry))
                    paths.append(str(target))
            out = javap(["-p", "-s", *paths]) if paths else ""
            current, pending_name = None, None
            for line in out.splitlines():
                m = HEADER.match(line)
                if m:
                    current = m.group(1).replace(".", "/")
                    parents = [p for g in (m.group(2), m.group(3)) if g for p in re.sub(r"<.*?>", "", g).split(",")]
                    self.members[current] = set()
                    self.supers[current] = [p.strip().replace(".", "/") for p in parents if p.strip()]
                    continue
                if current is None:
                    continue
                if line.strip().startswith("descriptor:") and pending_name:
                    self.members[current].add((pending_name, line.split(":", 1)[1].strip()))
                    pending_name = None
                    continue
                mm = MEMBER.match(line)
                if mm:
                    name = mm.group(1)
                    if "(" in line and name.split("$")[-1] == current.split("/")[-1].split("$")[-1]:  # Outer$Inner()
                        name = "<init>"
                    if line.strip().startswith("static {}"):
                        name = "<clinit>"
                    pending_name = name
            for o in batch:  # classes javap couldn't find don't exist in API 23
                self.members.setdefault(o, None)

    def has(self, owner, name, desc, field, seen=None):
        seen = seen or set()
        if owner in seen:
            return False
        seen.add(owner)
        self.load([owner])
        members = self.members.get(owner)
        if members is None:
            return False
        if any(n == name and (d == desc) for n, d in members):
            return True
        if name == "<init>":
            return False
        parents = self.supers.get(owner) or (["java/lang/Object"] if owner != "java/lang/Object" else [])
        return any(self.has(p, name, desc, field, seen) for p in parents)


def main():
    if not JAR.exists():
        sys.exit(f"missing {JAR}: sdkmanager 'platforms;android-23'")
    refs = app_references()
    api = Api()
    api.load(sorted({owner for owner, *_ in refs}))
    missing = []
    for (owner, name, desc, field), users in sorted(refs.items()):
        if api.members.get(owner) is None:
            missing.append(f"class {owner} (used by {', '.join(sorted(u for u in users if u))})")
        elif owner in BACKPORTED_OWNERS and name in BACKPORTED_NAMES and not field:
            continue
        elif not api.has(owner, name, desc, field):
            missing.append(f"{owner}.{name}{'' if field else desc} (used by {', '.join(sorted(u for u in users if u))})")
    print(f"checked {len(refs)} platform references against API 23")
    for m in missing:
        print("  MISSING", m)
    sys.exit(1 if missing else 0)


if __name__ == "__main__":
    main()
