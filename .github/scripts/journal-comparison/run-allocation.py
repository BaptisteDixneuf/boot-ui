"""One approved allocation replication; baseline overrides only five immutable-source class families."""
import hashlib
import json
import pathlib
import statistics
import subprocess
import time

root = pathlib.Path("comparison")
output = root / "evidence"
baseline = "233c74f9f6e5165bcd9e710c36ee6f60cbe04b3e"
cp = "bootui-spring-sample-app/target/test-classes:bootui-spring-sample-app/target/classes:" + (
    root / "classpath.txt").read_text().strip()
sources = root / "baseline-allocation-sources"
classes = root / "baseline-allocation-classes"
probe = root / "allocation-probe"
for directory in [sources, classes, probe]:
    directory.mkdir(exist_ok=True)
names = ["ApplicationFrames", "SqlPayload", "HttpPayload", "RestClientPayload", "CachePayload"]
source_manifest = []
for name in names:
    relative = f"bootui-engine/src/main/java/io/github/jdubois/bootui/engine/journal/{name}.java"
    data = subprocess.check_output(["git", "show", f"{baseline}:{relative}"])
    (sources / f"{name}.java").write_bytes(data)
    source_manifest.append({"path": relative, "baselineSha256": hashlib.sha256(data).hexdigest(),
                            "candidateSha256": hashlib.sha256(pathlib.Path(relative).read_bytes()).hexdigest()})
compiler = ["javac", "-J-XX:ActiveProcessorCount=1", "-J-Xmx768m", "--release", "17", "-cp", cp]
subprocess.run(compiler + ["-d", str(classes)] + [str(sources / f"{name}.java") for name in names],
               check=True, timeout=40)
subprocess.run(compiler + ["-d", str(probe), ".github/scripts/journal-comparison/DispatchAllocationProbe.java"],
               check=True, timeout=40)
rows = []
deadline = time.monotonic() + 120
with (output / "allocation-raw.log").open("w") as log:
    for pair in range(1, 6):
        for arm in ["baseline", "candidate"] if pair % 2 else ["candidate", "baseline"]:
            classpath = str(probe) + ":" + (str(classes) + ":" if arm == "baseline" else "") + cp
            result = subprocess.run(
                ["java", "-XX:ActiveProcessorCount=1", "-Xms128m", "-Xmx768m", "-cp", classpath,
                 "io.github.jdubois.bootui.engine.journal.DispatchAllocationProbe", f"{arm}-{pair}"],
                capture_output=True, text=True, timeout=min(15, deadline - time.monotonic()))
            log.write(result.stdout + result.stderr)
            log.flush()
            if result.returncode:
                raise SystemExit("Allocation probe failed: " + arm)
            rows.append(dict(item.split("=", 1) for item in result.stdout.strip().split()))
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
          "classes": [{"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                      for directory in [classes, probe] for path in sorted(directory.rglob("*.class"))]}
(output / "allocation.json").write_text(json.dumps(report, indent=2) + "\n")
print("Primary allocation criteria:", accepted)
