"""Sampled-stack, weighted-allocation and park attribution, not exact CPU accounting."""
import collections
import json
import pathlib
import re
import sys

source, output = map(pathlib.Path, sys.argv[1:])
data = json.loads(source.read_text())
events = data["recording"]["events"]
metrics = collections.defaultdict(collections.Counter)
counts = collections.Counter()
missing = collections.Counter()

def frames(values):
    stack = values.get("stackTrace")
    if stack is None:
        return []
    result = []
    for frame in stack["frames"]:
        method = frame["method"]
        result.append(method["type"]["name"].replace("/", ".") + "." + method["name"])
    return result

def category(stack):
    owned = [s for s in stack if s.startswith("io.github.jdubois.bootui.")
             and not s.startswith("io.github.jdubois.bootui.sample.")]
    return owned[0] if owned else (stack[0] if stack else "unavailable-stack")

def duration_nanos(value):
    match = re.fullmatch(r"PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?", value)
    if not match or not any(match.groups()):
        raise ValueError("Unrecognized park duration: " + repr(value))
    hours, minutes, seconds = (float(v) if v is not None else 0 for v in match.groups())
    return round((hours * 3600 + minutes * 60 + seconds) * 1e9)

for event in events:
    kind = event["type"]
    values = event["values"]
    counts[kind] += 1
    stack = frames(values)
    if not stack:
        missing[kind + ":stack"] += 1
    owner = category(stack)
    if kind == "jdk.ExecutionSample":
        thread = values.get("sampledThread")
        name = thread["javaName"] if thread is not None else "unavailable-thread"
        if thread is None:
            missing[kind + ":thread"] += 1
        metrics["executionSamplesByThread"][name] += 1
        metrics["executionSamplesByFirstBootUiOrLeafFrame"][owner] += 1
        for frame in dict.fromkeys(stack):
            metrics["executionSamplesInclusiveFrames"][frame] += 1
    elif kind == "jdk.ObjectAllocationSample":
        weight = values["weight"]
        if not isinstance(weight, int) or weight < 0:
            raise ValueError("Invalid allocation weight")
        allocated = values["objectClass"]["name"].replace("/", ".")
        thread = values.get("eventThread")
        name = thread["javaName"] if thread is not None else "unavailable-thread"
        if thread is None:
            missing[kind + ":thread"] += 1
        metrics["weightedAllocationByThread"][name] += weight
        metrics["weightedAllocationByFirstBootUiOrLeafFrame"][owner] += weight
        metrics["weightedAllocationByClass"][allocated] += weight
    elif kind == "jdk.ThreadPark":
        thread = values.get("eventThread")
        name = thread["javaName"] if thread is not None else "unavailable-thread"
        if thread is None:
            missing[kind + ":thread"] += 1
        nanos = duration_nanos(values["duration"])
        metrics["recordedParkNanosByThread"][name] += nanos
        metrics["recordedParkCountByFirstBootUiOrLeafFrame"][owner] += 1
    else:
        raise ValueError("Unexpected event type: " + kind)

if not counts["jdk.ExecutionSample"] or not counts["jdk.ObjectAllocationSample"]:
    raise SystemExit("Insufficient execution/allocation samples")
report = {"meaning": "Diagnostic sampled stacks and weighted allocations; not exact CPU, throughput, overhead or budget proof",
          "eventCounts": dict(counts), "missingDataCounts": dict(missing),
          "metrics": {name: [{"key": key, "value": value} for key, value in count.most_common(50)]
                      for name, count in metrics.items()}}
output.write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps({"eventCounts": dict(counts), "missingDataCounts": dict(missing)}, indent=2))
