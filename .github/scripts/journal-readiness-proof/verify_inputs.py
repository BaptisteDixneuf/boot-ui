"""Frozen source/patch/package guard for the one A/A-gated narrow proof. No branch or release ref mutation."""
import hashlib
import io
import json
import os
import pathlib
import subprocess
import sys
import tarfile
import zipfile

BASE = "f6366a306e55d98223fe81b34cda9b417615da48"
HARNESS = "65c765af81548f73f41760d26b607bad0daa7509"
ORIGIN = "fe08bb6573cbfac532122a4968a837fe4bbb0fab"
PATCH_SHA = "1c165104adffea06ad9e1c96caca5cfc5aa184cf02e8c31b72f6b663afe42bf6"
ROOT = pathlib.Path.cwd()
TEMP = pathlib.Path(os.environ["RUNNER_TEMP"])
EVIDENCE = ROOT / "comparison/evidence"
HELPER_FILES = {
    "CONTRIBUTING.md",
    "bootui-spring-sample-app/src/test/java/io/github/jdubois/bootui/sample/SampleExecutableJar.java",
    "bootui-spring-sample-app/src/test/java/io/github/jdubois/bootui/sample/SampleExecutableJarReadinessTests.java",
}
CANDIDATE_FILES = {
    "bootui-engine/src/main/java/io/github/jdubois/bootui/engine/journal/ApplicationFrames.java",
    "bootui-engine/src/test/java/io/github/jdubois/bootui/engine/journal/JournalBuildingBlocksTests.java",
    "docs/features/overview.md",
}
TOOLS = {".github/scripts/journal-readiness-proof/" + name for name in [
    "JournalPerformanceComparison.java", "DispatchAllocationProbe.java", "verify_inputs.py",
    "run-allocation.py", "capture-budgets.py", "assess.py"]}
TOOLS.add(".github/workflows/journal-readiness-proof.yml")
EXPECTED_HELPER_SHA = "0bae03b2c1737762ed7ba094c9054823bb585739dc09d3ae3f67f0911fa7ca24"
EXPECTED_FIXTURE_SHA = "69b7051f8866cc14143e69fead23ab2d19e8eceda0b7505c355723bd747ead68"


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def git(*args):
    return subprocess.check_output(["git", *args])


def save(name, value):
    (EVIDENCE / name).write_text(json.dumps(value, indent=2) + "\n")


def archive_manifest(commit):
    result = {}
    with tarfile.open(fileobj=io.BytesIO(git("archive", "--format=tar", commit))) as archive:
        for member in archive.getmembers():
            if member.isfile():
                result[member.name] = sha(archive.extractfile(member).read())
    return result


def sources():
    require(os.environ["BASELINE"] == BASE and os.environ["HARNESS"] == HARNESS
            and os.environ["NARROW_ORIGIN"] == ORIGIN, "Workflow source identities changed")
    changed = set(git("diff", "--name-only", HARNESS, "HEAD").decode().splitlines())
    require(changed == TOOLS, "Remote head must add exactly seven reviewed temporary tools")
    require(not git("status", "--porcelain").decode().strip(), "Dirty remote source tree")
    readiness = set(git("diff", "--name-only", BASE, HARNESS).decode().splitlines())
    require(readiness == HELPER_FILES, "Harness commit changed unrelated source")
    baseline = archive_manifest(BASE)
    common = archive_manifest(HARNESS)
    for path in baseline.keys() | common.keys():
        require(path in HELPER_FILES or baseline.get(path) == common.get(path),
                "Runtime source differs from f636: " + path)
    helper = "bootui-spring-sample-app/src/test/java/io/github/jdubois/bootui/sample/SampleExecutableJar.java"
    fixture = helper.replace("SampleExecutableJar.java", "SampleExecutableJarReadinessTests.java")
    require(common[helper] == EXPECTED_HELPER_SHA and common[fixture] == EXPECTED_FIXTURE_SHA,
            "Committed readiness helper or five fixtures differ from review")
    save("source-manifest.json", {"baseline": BASE, "baselineTree": git("rev-parse", BASE + "^{tree}").decode().strip(),
         "commonHarness": HARNESS, "head": git("rev-parse", "HEAD").decode().strip(),
         "headTree": git("rev-parse", "HEAD^{tree}").decode().strip(),
         "baselineFiles": baseline, "commonFiles": common,
         "temporaryFilesSha256": {p: sha((ROOT / p).read_bytes()) for p in sorted(TOOLS)}})


def candidate_source():
    aa = json.loads((EVIDENCE / "aa/results.json").read_text())
    require(aa["mode"] == "aa" and aa["pairs"] == 3 and aa["sameImmutableJar"]
            and aa["allControlsPassed"] and aa["baselineSha256"] == aa["candidateSha256"]
            and len(aa["runs"]) == 7 and all(r["armCaptureControlsPassed"] for r in aa["runs"])
            and abs(aa["baselineMeasuredSqlPerHttp"] - aa["candidateMeasuredSqlPerHttp"]) < 0.001,
            "A/A prerequisite did not pass; candidate source/build/execution forbidden")
    left = ROOT / "comparison/baseline.jar"
    require(sha(left.read_bytes()) == aa["baselineSha256"], "A/A baseline jar changed")
    destination = TEMP / "journal-candidate"
    destination.mkdir()
    data = git("archive", "--format=tar", HARNESS)
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for member in archive.getmembers():
            require(not pathlib.PurePosixPath(member.name).is_absolute()
                    and ".." not in pathlib.PurePosixPath(member.name).parts and not member.issym()
                    and not member.islnk(), "Unsafe archive member")
        archive.extractall(destination)
    patch = git("diff", "--binary", ORIGIN + "^", ORIGIN, "--", *sorted(CANDIDATE_FILES))
    require(sha(patch) == PATCH_SHA, "Preserved narrow-only patch changed")
    file = EVIDENCE / "narrow-candidate.patch"
    file.write_bytes(patch)
    subprocess.run(["git", "apply", "--check", str(file)], cwd=destination, check=True)
    subprocess.run(["git", "apply", str(file)], cwd=destination, check=True)
    common = archive_manifest(HARNESS)
    actual = {p: sha((destination / p).read_bytes()) for p in common}
    changed = {p for p in common if common[p] != actual[p]}
    require(changed == CANDIDATE_FILES, "Candidate changed more/less than the three narrow files")
    for name in HELPER_FILES:
        require(actual[name] == common[name], "Readiness helper/fixtures/doc differ between arms")
    save("candidate-source-manifest.json", {"baseRuntime": BASE, "commonHarness": HARNESS,
         "preservedNarrowOrigin": ORIGIN, "patchSha256": PATCH_SHA,
         "changedFiles": sorted(changed), "candidateFiles": actual})


def package(path):
    with zipfile.ZipFile(path) as jar:
        external, local, app = {}, {}, {}
        for name in jar.namelist():
            if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                content = jar.read(name)
                key = name.rsplit("/", 1)[-1]
                if key.startswith("bootui-"):
                    with zipfile.ZipFile(io.BytesIO(content)) as library:
                        local[key] = {n: sha(library.read(n)) for n in library.namelist()
                                      if not n.endswith("/") and not n.startswith("META-INF/maven/")
                                      and n != "META-INF/MANIFEST.MF"}
                else:
                    external[key] = sha(content)
            elif name.startswith("BOOT-INF/classes/") and not name.endswith("/"):
                app[name] = sha(jar.read(name))
    return {"jar": str(path), "sha256": sha(path.read_bytes()), "external": external,
            "bootuiClasses": local, "application": app}


def packages():
    left = package(ROOT / "comparison/baseline.jar")
    right = package(ROOT / "comparison/candidate.jar")
    require(left["external"] == right["external"] and left["application"] == right["application"],
            "Sample runtime or external dependency bytes differ")
    require(left["bootuiClasses"].keys() == right["bootuiClasses"].keys(), "BootUI library set changed")
    changes = []
    root = "io/github/jdubois/bootui/engine/journal/ApplicationFrames"
    for library, classes in left["bootuiClasses"].items():
        other = right["bootuiClasses"][library]
        for name in classes.keys() | other.keys():
            if classes.get(name) == other.get(name):
                continue
            require(library.startswith("bootui-engine-") and name.endswith(".class")
                    and (name == root + ".class" or name.startswith(root + "$")),
                    "Unexpected packaged drift: " + library + ":" + name)
            changes.append(library + ":" + name)
    require(any(name.endswith(":" + root + ".class") for name in changes),
            "Narrow formatter root class did not actually change")
    baseline = TEMP / "journal-baseline"
    candidate = TEMP / "journal-candidate"
    helper_classes = [
        "SampleExecutableJar", "SampleExecutableJarReadinessTests",
        "CaptureOverheadBenchmarkTest", "AgentOverheadBenchmarkIT"]
    common_classes = {}
    for arm, checkout in [("baseline", baseline), ("candidate", candidate)]:
        directory = checkout / "bootui-spring-sample-app/target/test-classes"
        require(directory.is_dir(), "Missing common compiled sample test directory: " + arm)
        common_classes[arm] = {
            path.relative_to(directory).as_posix(): sha(path.read_bytes())
            for path in sorted(directory.rglob("*")) if path.is_file()}
    require(common_classes["baseline"].keys() == common_classes["candidate"].keys(),
            "Common compiled test file set differs: new or missing root/nested/helper/resource")
    require(common_classes["baseline"] == common_classes["candidate"],
            "Common compiled sample test file bytes differ between arms")
    families = {}
    for name in helper_classes:
        root = "io/github/jdubois/bootui/sample/" + name
        family = sorted(path for path in common_classes["baseline"]
                        if path == root + ".class" or path.startswith(root + "$") and path.endswith(".class"))
        require(root + ".class" in family, "Required common helper root class missing: " + name)
        families[name] = family
    save("package-manifest.json", {"baseline": left, "candidate": right,
         "allowedClassChanges": changes, "commonCompiledTestFilesByArm": common_classes,
         "requiredCommonRootAndNestedFamilies": families,
         "qualification": "Only formatter root/nested class bytes differ. No broader payload, interning or admission change."})


if sys.argv[1:] == ["sources"]:
    sources()
elif sys.argv[1:] == ["candidate-source"]:
    candidate_source()
elif sys.argv[1:] == ["packages"]:
    packages()
else:
    raise SystemExit("Expected sources, candidate-source or packages")
