"""Preregistered allocation benefit and observed HTTP guard, with throughput interval kept separate."""
import json
import pathlib
import sys

root = pathlib.Path("comparison/evidence")
allocation = json.loads((root / "allocation.json").read_text())
http = json.loads((root / "results.json").read_text())
capture = json.loads((root / "capture/summary.json").read_text())
guard = (http["allControlsPassed"] and http["medianThroughputChangePercent"] >= 0
         and http["candidateMedianP50Millis"] <= http["baselineMedianP50Millis"]
         and http["candidateMedianP99Millis"] <= http["baselineMedianP99Millis"])
assessment = {"primaryAllocationCriteriaMet": allocation["primaryAllocationCriteriaMet"],
              "observedHttpGuardPassed": guard,
              "positiveThroughputInterval": http["lowThroughputChangePercent"] > 0,
              "originalCaptureAssertionsPassedBothArms": capture["originalAssertionsPassedBothArms"],
              "candidateSupportedForQualityReview": (allocation["primaryAllocationCriteriaMet"] and guard
                                                     and capture["originalAssertionsPassedBothArms"]),
              "qualification": "Observed HTTP medians are not statistical noninferiority. A crossing interval is not an HTTP speedup or 5% budget pass."}
(root / "assessment.json").write_text(json.dumps(assessment, indent=2) + "\n")
print(json.dumps(assessment, indent=2))
if not capture["originalAssertionsPassedBothArms"]:
    sys.exit("Original capture-budget assertions failed; raw reports and XML are preserved")
