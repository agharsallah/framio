#!/usr/bin/env python3
"""
API 23 guard: finds calls in the release APK that Android 6 (API 23) doesn't have.

Why: Lint's NewApi check reports nothing with this Kotlin/AGP combination, and two API 26/29 calls
crashed on the frame (03 LOG). This checks what actually ships: the dex after R8 and desugaring.
Every reference to a platform class, method or field (android.*, java.*, javax.*, ...) must exist in
API 23's android.jar, in the class itself or a superclass/interface. Desugared code (java.time → j$.time,
backported statics) no longer references the platform, so it passes by construction.

Callers are attributed by class name, which is why the release build keeps names (-dontobfuscate).
Code in SDK-aware libraries is skipped: they guard newer calls with Build.VERSION checks and declare
minSdk ≤ 23 (androidx, kotlinx-coroutines-android, OkHttp's platform layer, and the Kotlin stdlib's
JDK 7/8 layers, which it only loads after checking Build.VERSION.SDK_INT). Everything else, our own
code and plain-Java libraries, must pass, or be listed in tools/api23_allow.txt with
the SDK_INT check that guards it.

    python3 -I tools/api23_guard.py app/build/outputs/apk/release/app-release.apk
Exit code 1 if anything is missing. Needs `javap` (JDK), build-tools' dexdump and platforms;android-23.
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
ALLOW = ROOT / "tools/api23_allow.txt"
PLATFORM = ("android/", "java/", "javax/", "dalvik/", "org/json/", "org/xmlpull/", "org/w3c/", "org/xml/", "org/apache/http/")
# Libraries that check Build.VERSION.SDK_INT before calling newer APIs, and declare minSdk ≤ 23
SDK_AWARE = ("androidx.", "kotlinx.coroutines.android.", "okhttp3.internal.platform.", "okhttp3.internal.android.", "leakcanary.", "kotlin.internal.jdk7.", "kotlin.internal.jdk8.", "shark.", "curtains.", "_COROUTINE.", "j$.")

CLASS = re.compile(r"^  Class descriptor  : 'L([^;]+);'")
SUPER = re.compile(r"^  (?:Superclass|    #\d+)\s*: 'L([^;]+);'")
MEMBER_REF = re.compile(r"(invoke-[\w/-]+|[is](?:get|put)[\w-]*) .*?, L([\w/$]+);\.([\w<>$-]+):(\S+) // (method|field)@")
TYPE_REF = re.compile(r"(?:new-instance|const-class|check-cast|instance-of|filled-new-array|new-array) .*?, \[*L([\w/$]+);")
CATCH = re.compile(r"^\s+(L[\w/$]+;) -> 0x")
METHOD_START = re.compile(r"^[0-9a-f]+:\s+\|\[[0-9a-f]+\] (\S+:\S+)$")
SYNTHETIC = "$$ExternalSynthetic"


def dexdump():
    tools = sorted((SDK / "build-tools").glob("*/dexdump"))
    if not tools:
        sys.exit("dexdump not found: sdkmanager 'build-tools;37.0.0'")
    return str(tools[-1])


def dex_references(apk):
    """{(owner, name, desc, is_field) or (owner, None, None, False): {caller classes}}"""
    refs = {}      # platform ref -> {method or class that references it}
    callers = {}   # synthetic method -> {methods that call it}
    with zipfile.ZipFile(apk) as z, tempfile.TemporaryDirectory(prefix="api23-dex-") as tmp:
        dexes = [n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
        for name in dexes:
            path = Path(tmp) / name
            path.write_bytes(z.read(name))
            out = subprocess.run([dexdump(), "-d", str(path)], capture_output=True).stdout.decode("utf-8", "replace")
            current = None  # class, as a/b/C
            method = None   # a.b.C.name:(desc)ret
            in_header = False
            pending = []    # (pc, kind, key) of the current method, placed on a line once its positions are read
            positions = []  # (pc, line) of the current method
            in_positions = False

            def flush():
                # R8's mapping tells, per output line range, which original method the code came from (merged
                # lambdas hold many): give each reference the line of its instruction
                for pc, kind, key, site in pending:
                    line_no = None
                    for ppc, ln in positions:
                        if ppc <= pc:
                            line_no = ln
                        else:
                            break
                    where = f"{site}@{line_no}" if line_no is not None and ":" in site else site
                    (refs if kind == "ref" else callers).setdefault(key, set()).add(where)
                pending.clear()
                positions.clear()

            for line in out.splitlines():
                if in_positions:
                    pm = POSITION.match(line)
                    if pm:
                        positions.append((int(pm.group(1), 16), int(pm.group(2))))
                        continue
                    in_positions = False
                if line.startswith("      positions     :"):
                    in_positions = True
                    continue
                m = CLASS.match(line)
                if m:
                    flush()
                    current, method, in_header = m.group(1), None, True
                    continue
                if in_header:
                    if line.startswith(("  Access flags", "  Interfaces", "  Superclass", "    #")):
                        s = SUPER.match(line)
                        if s and s.group(1).startswith(PLATFORM):
                            refs.setdefault((s.group(1), None, None, False), set()).add(current.replace("/", "."))
                        continue
                    if line.startswith(("  Static fields", "  Direct methods")):
                        in_header = False
                m = METHOD_START.search(line)
                if m:
                    flush()
                    method = m.group(1)
                    continue
                if current is None:
                    continue
                site = method or current.replace("/", ".")
                pcm = PC.search(line)
                pc = int(pcm.group(1), 16) if pcm else -1
                m = MEMBER_REF.search(line)
                if m:
                    _, owner, member, desc, kind = m.groups()
                    if owner.startswith(PLATFORM):
                        pending.append((pc, "ref", (owner, member, desc, kind == "field"), site))
                    elif SYNTHETIC in owner:
                        pending.append((pc, "call", f"{owner.replace('/', '.')}.{member}:{desc}", site))
                    continue
                m = TYPE_REF.search(line) or CATCH.match(line)
                if m:
                    owner = m.group(1).strip("L;")
                    if owner.startswith(PLATFORM):
                        pending.append((pc, "ref", (owner, None, None, False), site))
            flush()
    # R8 moves code into shared synthetic outlines named after an unrelated class (kotlin.math.MathKt$$External…):
    # attribute what they reference to whoever calls them (at the line of the call).
    return {ref: {c for site in sites for c in attribute(site, callers, set())} for ref, sites in refs.items()}


def attribute(site, callers, seen):
    bare, _, line_no = site.partition("@")
    cls, name = (bare.split(":")[0].rsplit(".", 1) if ":" in bare else (bare, None))
    # The line of the instruction says which original method the code came from (exact for merged lambda groups)
    args = bare.split(":", 1)[1].split(")")[0].lstrip("(") if ":" in bare else None
    origin = next((o for lo, hi, o in RANGES.get((cls, name, args), ()) if line_no and lo <= int(line_no) <= hi), None)
    if origin is not None and not is_synthetic(origin):
        return {origin}
    if origin is not None and "$$ExternalSyntheticLambda" in cls:
        # A merged lambda group's own glue (unpacking captured values before a case) belongs to the case after it
        after = sorted((lo, o) for lo, hi, o in RANGES.get((cls, name, args), ()) if lo > int(line_no) and not is_synthetic(o))
        if after:
            return {after[0][1]}
    if SYNTHETIC not in cls or bare in seen or bare not in callers:
        return {origin} if origin else ORIGINS.get((cls, name)) or {cls}
    seen.add(bare)
    return {c for caller in callers[bare] for c in attribute(caller, callers, seen)}


ORIGINS = {}  # (class, method) in the dex -> classes it came from, per R8's mapping.txt
RANGES = {}   # (class, method, args) in the dex -> [(first line, last line, original class)] for the code at those lines
POSITION = re.compile(r"^        0x([0-9a-f]+) line=(\d+)$")
PC = re.compile(r"\|([0-9a-f]{4}): ")
MAP_CLASS = re.compile(r"^(\S+) -> (\S+):$")
MAP_METHOD = re.compile(r"^    (?:(\d+:\d+):)?\S+ ([\w.$<>-]+)\(.*?\)(?::[\d:]+)? -> ([\w$<>-]+)$")


def is_synthetic(cls):
    return SYNTHETIC in cls or "$$InternalSyntheticLambda" in cls or "$$Lambda" in cls


def load_mapping(path):
    """R8 moves and inlines methods across classes (androidx code can end up in kotlin.ResultKt). Its mapping
    lists, for each method in the output, the original methods it holds. Lines sharing a pc range are one
    inlining chain, innermost first; the last line of a chain is the method the code really belongs to.
    Ranges are kept per output method signature (overloads share a name) for line-exact attribution."""
    if not path.exists():
        print(f"api23 guard: no {path}, attributing by output class only")
        return
    chains = {}  # (residual class, name) -> {range or line number: [outermost original class, residual args]}
    original = residual = None
    last = None  # the entry of the previous method line, for a residualsignature comment that follows it
    for n, line in enumerate(path.read_text().splitlines()):
        m = MAP_CLASS.match(line)
        if m:
            original, residual = m.groups()
            last = None
            continue
        r = RESIDUAL_SIG.search(line)
        if r and last is not None:
            last[1] = r.group(1)
            continue
        m = MAP_METHOD.match(line)
        if not m or residual is None:
            continue
        rng, qualified, name = m.groups()
        cls = qualified.rsplit(".", 1)[0] if "." in qualified else original
        ranges = chains.setdefault((residual, name), {})
        entry = ranges.setdefault(rng or n, [cls, None])
        # Later lines of a range overwrite: outermost wins. Except a merged synthetic lambda's own `invoke` frame
        # (R8's lambda groups): the code there belongs to the lambda body inlined into it, one frame further in.
        if not (is_synthetic(cls) and not is_synthetic(entry[0])):
            entry[0] = cls
        if "." not in qualified:  # the output method's own frame: its arguments tell overloads apart
            a = MAP_ARGS.match(line)
            entry[1] = "".join(descriptor(t.strip()) for t in a.group(1).split(",") if t.strip()) if a else None
            last = entry
        else:
            last = None
    for (cls, name), outer in chains.items():
        ORIGINS[(cls, name)] = {e[0] for e in outer.values()}
        for rng, (origin, args) in outer.items():
            if isinstance(rng, str):
                lo, hi = (int(x) for x in rng.split(":"))
                RANGES.setdefault((cls, name, args), []).append((lo, hi, origin))


PRIMITIVES = {"int": "I", "long": "J", "boolean": "Z", "byte": "B", "char": "C", "short": "S", "float": "F", "double": "D", "void": "V"}
MAP_ARGS = re.compile(r"^    (?:\d+:\d+:)?\S+ [\w$<>-]+\((.*?)\)")
RESIDUAL_SIG = re.compile(r'"com\.android\.tools\.r8\.residualsignature","signature":"\((.*?)\)')


def descriptor(t):
    if t.endswith("[]"):
        return "[" + descriptor(t[:-2])
    return PRIMITIVES.get(t) or "L" + t.replace(".", "/") + ";"


HEADER = re.compile(r"^(?:[\w ]*?)(?:class|interface) ([\w.$]+)(?:<[^{]*?>)?(?: extends ([\w.$, <>?]+?))?(?: implements ([\w.$, <>?]+?))? ?\{$")
MEMBER = re.compile(r"^  .*?([\w$<>]+)(?:\(.*\))?(?: throws .*)?;$")


class Api:
    """Members of API 23 classes, read lazily from android.jar with javap -s."""

    def __init__(self):
        self.members = {}  # owner -> set of (name, desc), None if the class doesn't exist
        self.supers = {}
        self.jar = zipfile.ZipFile(JAR)
        self.names = set(self.jar.namelist())
        self.tmp = Path(tempfile.mkdtemp(prefix="api23-jar-"))

    def load(self, owners):
        todo = [o for o in owners if o not in self.members]
        for i in range(0, len(todo), 100):
            batch = todo[i:i + 100]
            paths = []
            for o in batch:  # javap -cp would read java.* from the JDK itself: extract and pass file paths
                entry = o + ".class"
                if entry in self.names:
                    target = self.tmp / entry
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(self.jar.read(entry))
                    paths.append(str(target))
            out = subprocess.run(["javap", "-p", "-s", *paths], capture_output=True, text=True).stdout if paths else ""
            current, pending = None, None
            for line in out.splitlines():
                m = HEADER.match(line)
                if m:
                    current = m.group(1).replace(".", "/")
                    # javap prints nested classes as Outer.Inner: map back to Outer$Inner
                    for o in batch:
                        if o.replace("$", "/") == current:
                            current = o
                    parents = [p for g in (m.group(2), m.group(3)) if g for p in re.sub(r"<.*?>", "", g).split(",")]
                    self.members[current] = set()
                    self.supers[current] = [self._binary(p.strip()) for p in parents if p.strip()]
                    continue
                if current is None:
                    continue
                if line.strip().startswith("descriptor:") and pending:
                    self.members[current].add((pending, line.split(":", 1)[1].strip()))
                    pending = None
                    continue
                mm = MEMBER.match(line)
                if mm:
                    name = mm.group(1)
                    if "(" in line and name.split("$")[-1] == current.split("/")[-1].split("$")[-1]:
                        name = "<init>"
                    if line.strip().startswith("static {}"):
                        name = "<clinit>"
                    pending = name
            for o in batch:
                self.members.setdefault(o, None)

    def _binary(self, dotted):
        """java.util.Map.Entry → java/util/Map$Entry, using which class files exist."""
        parts = dotted.split(".")
        for split in range(len(parts), 0, -1):
            candidate = "/".join(parts[:split]) + "".join("$" + p for p in parts[split:])
            if candidate + ".class" in self.names:
                return candidate
        return dotted.replace(".", "/")

    def exists(self, owner):
        self.load([owner])
        return self.members.get(owner) is not None

    def has(self, owner, name, desc, seen=None):
        seen = seen if seen is not None else set()
        if owner in seen:
            return False
        seen.add(owner)
        if not self.exists(owner):
            return False
        if (name, desc) in self.members[owner]:
            return True
        if name == "<init>":
            return False
        parents = self.supers.get(owner) or (["java/lang/Object"] if owner != "java/lang/Object" else [])
        return any(self.has(p, name, desc, seen) for p in parents)


def allowed():
    if not ALLOW.exists():
        return set()
    return {line.split("#", 1)[0].strip() for line in ALLOW.read_text().splitlines() if line.split("#", 1)[0].strip()}


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    if not JAR.exists():
        sys.exit(f"missing {JAR}: sdkmanager 'platforms;android-23'")
    apk = Path(sys.argv[1])
    load_mapping(apk.parent.parent.parent / "mapping" / apk.parent.name / "mapping.txt")
    refs = dex_references(apk)
    api = Api()
    api.load(sorted({owner for owner, *_ in refs}))
    allow = allowed()
    missing, skipped = [], 0
    for (owner, name, desc, field), callers in sorted(refs.items(), key=lambda kv: (kv[0][0], kv[0][1] or "", kv[0][2] or "")):
        ours = sorted(c for c in callers if not c.startswith(SDK_AWARE))
        if not ours:
            skipped += 1
            continue
        label = owner if name is None else f"{owner}.{name}{'' if field else desc}"
        ok = api.exists(owner) if name is None else api.has(owner, name, desc)
        member = owner.replace("/", ".") + ("" if name is None else "." + name)
        if not ok and member not in allow and owner.replace("/", ".") not in allow:
            missing.append(f"{label}\n      used by {', '.join(ours[:6])}{' …' if len(ours) > 6 else ''}")
    print(f"api23 guard: {len(refs)} platform references in {Path(sys.argv[1]).name}, {skipped} only from SDK-aware libraries")
    for m in missing:
        print("  MISSING", m)
    print("api23 guard: " + ("FAIL" if missing else "OK"))
    sys.exit(1 if missing else 0)


if __name__ == "__main__":
    main()
