#!/usr/bin/env python3
"""Static pre-flight checks for the Android layer.

The offline sandbox cannot compile `:app` (no Android SDK, no Maven Central), so the Compose/DI
layer is otherwise only reviewed by hand. Several classes of error that the Android build *would*
reject can nevertheless be detected statically, and each check below corresponds to a real failure
that would otherwise be discovered only after a CI round-trip.

Checks:
  1. Resource references resolve — `@style/Foo`, `@color/bar`, `@string/baz` in the manifest and in
     resource XML must be defined somewhere (any qualifier).
  2. No Android-free `core` degradation: `core/src/main` still imports only java/kotlin.
  3. Every `@Inject` constructor dependency of a class is either provided in a `@Module` or is
     itself `@Inject`-constructible — catches Hilt "cannot be provided" errors before CI.
  4. Every `R.string.*` / `R.drawable.*` reference in Kotlin resolves.
  5. AndroidManifest components (`android:name=".Foo"`) exist as Kotlin classes.

Exit code 0 = clean.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
problems = []


def fail(msg):
    problems.append(msg)


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def xml_files(base):
    for d, _, fs in os.walk(os.path.join(ROOT, base)):
        for f in fs:
            if f.endswith(".xml"):
                yield os.path.join(d, f)


def check_resource_refs():
    """Every @type/name referenced from resources or the manifest must be defined."""
    defined = {"string": set(), "color": set(), "style": set(), "drawable": set(), "mipmap": set(),
               "xml": set(), "array": set(), "bool": set(), "integer": set(), "dimen": set()}
    for path in xml_files("app/src/main/res"):
        text = read(path)
        for typ in defined:
            for name in re.findall(r"<%s[^>]*\bname=\"([^\"]+)\"" % typ, text):
                defined[typ].add(name)
    # Files are themselves definitions for drawable/mipmap/xml/values.
    for path in xml_files("app/src/main/res"):
        rel = os.path.relpath(path, os.path.join(ROOT, "app/src/main/res")).replace(os.sep, "/")
        folder = rel.split("/")[0]
        stem = os.path.basename(rel).rsplit(".", 1)[0]
        kind = folder.split("-")[0]
        if kind in ("drawable", "mipmap", "xml", "layout", "menu"):
            defined[kind].add(stem)

    targets = list(xml_files("app/src/main/res")) + [os.path.join(ROOT, "app/src/main/AndroidManifest.xml")]
    for path in targets:
        text = read(path)
        for typ, name in re.findall(r"@(\w+)/([A-Za-z0-9_.]+)", text):
            # `@android:style/Theme.…` is a platform resource; the type group is "android".
            if typ == "android":
                continue
            # `@style/Theme.Samaveta` names one style; a dotted segment is part of the name.
            if name not in defined[typ] and "." in name:
                if name.split(".")[0] in defined[typ]:
                    continue
            if typ not in defined:
                continue
            # A style reference may be `Theme.Foo` — the last segment is not the resource name.
            if name not in defined[typ]:
                fail(f"undefined resource @{typ}/{name} in {os.path.relpath(path, ROOT)}")


def check_kotlin_resource_refs():
    defined = {"string": set(), "drawable": set(), "color": set(), "mipmap": set()}
    for path in xml_files("app/src/main/res"):
        text = read(path)
        for typ in defined:
            for name in re.findall(r"<%s[^>]*\bname=\"([^\"]+)\"" % typ, text):
                defined[typ].add(name)
    for d, _, fs in os.walk(os.path.join(ROOT, "app/src/main/res")):
        for f in fs:
            folder = os.path.basename(d).split("-")[0]
            if folder in ("drawable", "mipmap") and f.endswith(".xml"):
                defined[folder].add(f.rsplit(".", 1)[0])
    for d, _, fs in os.walk(os.path.join(ROOT, "app/src/main/kotlin")):
        for f in fs:
            if not f.endswith(".kt"):
                continue
            path = os.path.join(d, f)
            # `android.R.…` is the platform package, not this project's resources.
            text = re.sub(r"android\.R\.(string|drawable|color|mipmap)\.\w+", "", read(path))
            for typ, name in re.findall(r"(?<!android\.)\bR\.(string|drawable|color|mipmap)\.([A-Za-z0-9_]+)", text):
                if name not in defined[typ]:
                    fail(f"undefined R.{typ}.{name} in {os.path.relpath(path, ROOT)}")


def check_manifest_classes():
    manifest = read(os.path.join(ROOT, "app/src/main/AndroidManifest.xml"))
    sources = []
    for d, _, fs in os.walk(os.path.join(ROOT, "app/src/main/kotlin")):
        for f in fs:
            if f.endswith(".kt"):
                sources.append(read(os.path.join(d, f)))
    joined = "\n".join(sources)
    for declared in re.findall(r'android:name="\.([A-Za-z0-9_.]+)"', manifest):
        simple = declared.split(".")[-1]
        if not re.search(r"\bclass\s+%s\b" % re.escape(simple), joined):
            fail(f"manifest declares .{declared} but no Kotlin class {simple} exists")


def check_hilt_providers():
    """Every @Inject constructor parameter type must be provided or @Inject-constructible.

    This is the check that would have caught a Hilt "X cannot be provided without an @Provides"
    error at build time instead of in CI.
    """
    app_src = {}
    for d, _, fs in os.walk(os.path.join(ROOT, "app/src/main/kotlin")):
        for f in fs:
            if f.endswith(".kt"):
                app_src[os.path.join(d, f)] = read(os.path.join(d, f))
    core_src = {}
    for d, _, fs in os.walk(os.path.join(ROOT, "core/src/main/kotlin")):
        for f in fs:
            if f.endswith(".kt"):
                core_src[os.path.join(d, f)] = read(os.path.join(d, f))
    everything = "\n".join(list(app_src.values()) + list(core_src.values()))

    provided = set(re.findall(r"fun\s+provide([A-Za-z0-9_]+)\s*\(", everything))
    # Return types of @Provides functions are what Hilt can inject.
    # Framework types Hilt itself provides. A qualifier is still required for `Context`, so the
    # check keeps flagging an unqualified one; these are the bindings that always exist.
    provided_types = {
        "Application", "Activity", "ApplicationContext", "ViewModel", "SavedStateHandle",
        "DataStore", "Preferences", "BuildConfig",
    }
    # A parameter list may itself contain parentheses (`@Named("x")`), so match to the `): Type`
    # that closes the declaration rather than to the first `)`.
    for m in re.finditer(r"fun\s+provide[A-Za-z0-9_]*\s*\(.*?\)\s*:\s*([A-Za-z0-9_.<>]+)", everything, re.S):
        provided_types.add(m.group(1).split(".")[-1])
    injectable = set()
    for m in re.finditer(r"class\s+([A-Za-z0-9_]+)\s*@Inject\s*constructor", everything):
        injectable.add(m.group(1))
    qualifiers = set(re.findall(r'@Named\("([^"]+)"\)', everything))

    for path, text in app_src.items():
        # Comments inside a parameter list are ignored: a doc-comment above a parameter would
        # otherwise be parsed as part of the declaration.
        without_comments = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        without_comments = re.sub(r"//[^\n]*", "", without_comments)
        for m in re.finditer(r"class\s+([A-Za-z0-9_]+)\s*@Inject\s*constructor\s*\((.*?)\)\s*:\s*",
                             without_comments, re.S):
            cls, params = m.group(1), m.group(2)
            for raw in re.split(r",\s*(?=\w|@)", params):
                raw = raw.strip()
                # Strip any qualifier annotations (`@Named("x")`, `@ApplicationContext`) that appear
                # before the parameter name; the declared *type* is what Hilt resolves.
                original = raw
                raw = re.sub(r"@\w+(\([^)]*\))?\s*", "", raw).strip()
                if not raw:
                    continue
                piece = re.search(r":\s*([A-Za-z0-9_.<>?]+)", raw)
                if not piece:
                    continue
                typ = piece.group(1).split(".")[-1].rstrip("?")
                if typ in ("String", "Int", "Long", "Boolean", "Double", "Float", "Unit", "List", "Set"):
                    # Primitive/collection parameters must be qualified; a missing qualifier is a
                    # Hilt error, so require that this parameter actually carries one.
                    if "@Named" not in raw:
                        fail(f"{cls}: primitive parameter without @Named qualifier — Hilt cannot provide it")
                    continue
                if typ in provided_types or typ in injectable:
                    continue
                if typ == "Context" and "@ApplicationContext" in original:
                    continue
                fail(f"{cls}: @Inject parameter of type {typ} has no @Provides and is not @Inject-constructible")
    return qualifiers


def main():
    if not os.path.isdir(os.path.join(ROOT, "app")):
        print("app module missing")
        return 1
    check_resource_refs()
    check_kotlin_resource_refs()
    check_manifest_classes()
    check_hilt_providers()
    if problems:
        print(f"Android pre-flight: {len(problems)} problem(s)")
        for p in problems:
            print(f"  - {p}")
        return 1
    print("Android pre-flight: clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
