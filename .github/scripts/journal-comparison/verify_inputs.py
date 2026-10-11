"""Temporary allocation-comparison input guard; rejects unrelated runtime drift."""
import hashlib
import io
import json
import pathlib
import subprocess
import sys
import zipfile

allowed = {
    "bootui-engine/src/main/java/io/github/jdubois/bootui/engine/journal/" + name + ".java"
    for name in ["ApplicationFrames"]
}
allowed.update({"bootui-engine/src/test/java/io/github/jdubois/bootui/engine/journal/JournalBuildingBlocksTests.java",
                "docs/features/overview.md"})
allowed.update({".github/scripts/journal-comparison/" + name for name in [
    "JournalPerformanceComparison.java", "DispatchAllocationProbe.java", "verify_inputs.py",
    "run-allocation.py", "assess.py", "capture-budgets.py"]})
allowed.add(".github/workflows/journal-format-comparison.yml")
changed = subprocess.check_output(
    ["git", "diff", "--name-only", "233c74f9f6e5165bcd9e710c36ee6f60cbe04b3e", "HEAD"], text=True
).splitlines()
unexpected = [p for p in changed if p not in allowed]
if unexpected:
    raise SystemExit("Unexpected source drift: " + repr(unexpected))
if sys.argv[1:] == ["--sources-only"]:
    print("Exact source whitelist passed before builds")
    raise SystemExit(0)
baseline, candidate, output = map(pathlib.Path, sys.argv[1:])

def package(path):
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        external = {}
        local = {}
        app = {}
        for name in names:
            if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                content = jar.read(name)
                key = name.rsplit("/", 1)[-1]
                if key.startswith("bootui-"):
                    with zipfile.ZipFile(io.BytesIO(content)) as library:
                        local[key] = {n: hashlib.sha256(library.read(n)).hexdigest()
                                      for n in library.namelist()
                                      if not n.endswith("/") and not n.startswith("META-INF/maven/")
                                      and n != "META-INF/MANIFEST.MF"}
                else:
                    external[key] = hashlib.sha256(content).hexdigest()
            elif name.startswith("BOOT-INF/classes/") and not name.endswith("/"):
                app[name] = hashlib.sha256(jar.read(name)).hexdigest()
        return {"jar": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "external": external, "bootuiClasses": local, "application": app}

left, right = package(baseline), package(candidate)
if left["external"] != right["external"] or left["application"] != right["application"]:
    raise SystemExit("Application or external dependency bytes differ")
class_changes = []
roots = {
    "io/github/jdubois/bootui/engine/journal/" + name
    for name in ["ApplicationFrames"]
}
if left["bootuiClasses"].keys() != right["bootuiClasses"].keys():
    raise SystemExit("BootUI library set differs")
for library, classes in left["bootuiClasses"].items():
    proposed = right["bootuiClasses"][library]
    for name in classes.keys() | proposed.keys():
        if classes.get(name) != proposed.get(name):
            owned = name.endswith(".class") and any(
                name == root + ".class" or name.startswith(root + "$") for root in roots
            )
            if not library.startswith("bootui-engine-") or not owned:
                raise SystemExit("Unexpected packaged class change: " + library + ":" + name)
            class_changes.append(library + ":" + name)
if not all(any(change.endswith(":" + root + ".class") for change in class_changes) for root in roots):
    raise SystemExit("The narrow frame root class must differ: " + repr(class_changes))
manifest_files = subprocess.check_output(
    ["git", "ls-files", "*pom.xml", "*package.json", "*package-lock.json", ".mvn/*"], text=True
).splitlines()
manifests = {name: hashlib.sha256(pathlib.Path(name).read_bytes()).hexdigest()
             for name in manifest_files if pathlib.Path(name).is_file()}
output.write_text(json.dumps({"baseline": left, "candidate": right,
                              "classChanges": class_changes, "manifestDigests": manifests}, indent=2) + "\n")
