"""One approved allocation replication; baseline overrides only five immutable-source class families."""
import hashlib
import json
import math
import pathlib
import statistics
import subprocess
import time
import zipfile

root = pathlib.Path("comparison")
output = root / "evidence"
baseline = "233c74f9f6e5165bcd9e710c36ee6f60cbe04b3e"
cp = "bootui-spring-sample-app/target/test-classes:bootui-spring-sample-app/target/classes:" + (
    root / "classpath.txt").read_text().strip()
probe = root / "allocation-probe"
probe.mkdir(exist_ok=True)
names = ["ApplicationFrames", "SqlPayload", "HttpPayload", "RestClientPayload", "CachePayload"]
source_manifest = []
for name in names:
    relative = f"bootui-engine/src/main/java/io/github/jdubois/bootui/engine/journal/{name}.java"
    data = subprocess.check_output(["git", "show", f"{baseline}:{relative}"])
    source_manifest.append({"path": relative, "baselineSha256": hashlib.sha256(data).hexdigest(),
                            "candidateSha256": hashlib.sha256(pathlib.Path(relative).read_bytes()).hexdigest()})
compiler = ["javac", "-J-XX:ActiveProcessorCount=1", "-J-Xmx768m", "--release", "17", "-cp", cp]
subprocess.run(compiler + ["-d", str(probe), ".github/scripts/journal-comparison/DispatchAllocationProbe.java"],
               check=True, timeout=40)
engines = {}
engine_manifests = {}
for arm in ["baseline", "candidate"]:
    with zipfile.ZipFile(root / f"{arm}.jar") as application:
        entries = [entry for entry in application.namelist()
                   if entry.startswith("BOOT-INF/lib/bootui-engine-") and entry.endswith(".jar")]
        if len(entries) != 1:
            raise SystemExit("Expected one packaged engine: " + arm)
        data = application.read(entries[0])
    path = root / f"{arm}-allocation-engine.jar"
    path.write_bytes(data)
    engines[arm] = path
    with zipfile.ZipFile(path) as engine:
        selected = {entry: hashlib.sha256(engine.read(entry)).hexdigest()
                    for entry in engine.namelist() if entry.endswith(".class") and any(
                        entry == f"io/github/jdubois/bootui/engine/journal/{name}.class"
                        or entry.startswith(f"io/github/jdubois/bootui/engine/journal/{name}$") for name in names)}
    engine_manifests[arm] = {"engineJarSha256": hashlib.sha256(data).hexdigest(),
                             "selectedPackagedClasses": selected}
rows = []
deadline = time.monotonic() + 120
with (output / "allocation-raw.log").open("w") as log:
    for pair in range(1, 6):
        for arm in ["baseline", "candidate"] if pair % 2 else ["candidate", "baseline"]:
            classpath = str(probe) + ":" + str(engines[arm]) + ":" + cp
            engine_manifests[arm]["classpath"] = classpath
            result = subprocess.run(
                ["java", "-XX:ActiveProcessorCount=1", "-Xms128m", "-Xmx768m", "-cp", classpath,
                 "io.github.jdubois.bootui.engine.journal.DispatchAllocationProbe", f"{arm}-{pair}"],
                capture_output=True, text=True, timeout=min(15, deadline - time.monotonic()))
            log.write(result.stdout + result.stderr)
            log.flush()
            if result.returncode:
                raise SystemExit("Allocation probe failed: " + arm)
            row = dict(item.split("=", 1) for item in result.stdout.strip().split())
            for key in ["operations", "allocated_bytes", "cpu_nanos", "dictionary_entries", "dictionary_bytes", "processors"]:
                row[key] = int(row[key])
            for key in ["bytes_per_operation", "cpu_ns_per_operation", "wall_seconds"]:
                row[key] = float(row[key])
                if not math.isfinite(row[key]) or row[key] <= 0:
                    raise SystemExit("Invalid numeric probe output")
            if (row["operations"] <= 0 or row["operations"] % 256 or row["allocated_bytes"] <= 0
                    or row["cpu_nanos"] <= 0 or row["dictionary_entries"] != 7 or row["dictionary_bytes"] != 817
                    or row["processors"] != 1 or not row["java"].startswith("17.")
                    or row["wall_seconds"] > 15
                    or abs(row["bytes_per_operation"] - row["allocated_bytes"] / row["operations"]) > 0.00001
                    or abs(row["cpu_ns_per_operation"] - row["cpu_nanos"] / row["operations"]) > 0.00001):
                raise SystemExit("Probe output failed counters/caps/version controls")
            rows.append(row)
if len(rows) != 10 or len({row["label"] for row in rows}) != 10:
    raise SystemExit("Incomplete or duplicate cells")
pairs = []
for pair in range(1, 6):
    left = next(r for r in rows if r["label"] == f"baseline-{pair}")
    right = next(r for r in rows if r["label"] == f"candidate-{pair}")
    pairs.append({"pair": pair, "allocationReductionPercent":
                  100 * (1 - float(right["bytes_per_operation"]) / float(left["bytes_per_operation"])),
                  "cpuChangePercent":
                  100 * (float(right["cpu_ns_per_operation"]) / float(left["cpu_ns_per_operation"]) - 1)})
accepted = all(pair["allocationReductionPercent"] > 0 for pair in pairs) and statistics.median(
    pair["cpuChangePercent"] for pair in pairs) <= 0
report = {"baselineSource": baseline, "sourceManifest": source_manifest, "rows": rows, "pairs": pairs,
          "primaryAllocationCriteriaMet": accepted, "warmupOperationsPerCell": 20000,
          "workload": "Three prebuilt StackFrame fixtures; fresh pending ApplicationFrames and SQL/HTTP records per operation; path cycles through 256 values in complete 256-operation batches",
          "variant": "Bounded path distribution, not bit-identical to the earlier local increasing-index diagnostic",
          "notMeasured": "Real StackWalker/capture, journal dispatch, HTTP throughput and the 5% journal budget",
          "engineManifests": engine_manifests,
          "probeSourceSha256": hashlib.sha256(pathlib.Path(
              ".github/scripts/journal-comparison/DispatchAllocationProbe.java").read_bytes()).hexdigest(),
          "classes": [{"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                      for path in sorted(probe.rglob("*.class"))]}
(output / "allocation.json").write_text(json.dumps(report, indent=2) + "\n")
print("Primary allocation criteria:", accepted)
