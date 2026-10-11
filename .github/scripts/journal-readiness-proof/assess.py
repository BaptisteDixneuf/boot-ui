"""Source-specific A/A capture prerequisite, allocation, original capture and observed HTTP guard."""
import json
import pathlib
import sys

root = pathlib.Path("comparison/evidence")
paths = {"aa": "aa/results.json", "allocation": "allocation.json",
        "capture": "capture/summary.json", "http": "candidate/results.json"}
reports = {name: json.loads((root / path).read_text()) if (root / path).is_file() else None
          for name, path in paths.items()}
stage_evidence = {name: "EMITTED" if report is not None else "NOT_EMITTED_NO_PASS_CLAIM"
                 for name, report in reports.items()}
aa, allocation, capture, http = [reports[name] for name in ["aa", "allocation", "capture", "http"]]
aa_ok = bool(aa and aa["mode"] == "aa" and aa["pairs"] == 3 and aa["sameImmutableJar"]
        and aa["baselineSha256"] == aa["candidateSha256"] and aa["allControlsPassed"]
        and len(aa["runs"]) == 7 and all(r["armCaptureControlsPassed"] for r in aa["runs"])
        and abs(aa["baselineMeasuredSqlPerHttp"] - aa["candidateMeasuredSqlPerHttp"]) < 0.001)
primary = bool(allocation and allocation["primaryAllocationCriteriaMet"])
original = bool(capture and capture["originalAssertionsPassedBothArms"])
observed = bool(http and http["mode"] == "candidate" and http["pairs"] == 15 and http["allControlsPassed"]
            and len(http["runs"]) == 31 and all(r["armCaptureControlsPassed"] for r in http["runs"])
            and http["medianThroughputChangePercent"] >= 0
            and http["candidateMedianP50Millis"] <= http["baselineMedianP50Millis"]
            and http["candidateMedianP99Millis"] <= http["baselineMedianP99Millis"]
            and abs(http["baselineMeasuredSqlPerHttp"] - http["candidateMeasuredSqlPerHttp"]) < 0.001)
result = {"stageEvidence": stage_evidence, "aaCaptureControlsPassed": aa_ok, "aaPerformanceInference": False,
          "primaryAllocationCriteriaMet": primary, "originalCaptureAssertionsPassedBothArms": original,
          "observedHttpGuardPassed": observed,
          "positiveThroughputInterval": (http["lowThroughputChangePercent"] > 0
                                         if http and "lowThroughputChangePercent" in http else None),
          "candidateSupportedForQualityReview": aa_ok and primary and original and observed,
          "historicalFailedGuardRescored": False, "journalBudgetClaim": False,
          "qualification": "Crossing interval means no HTTP win. Observed medians are not statistical noninferiority. Native full CI is a separate merge prerequisite."}
(root / "assessment.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, indent=2))
if not result["candidateSupportedForQualityReview"]:
    sys.exit("Reject narrow candidate; preserve failed controls without retry or threshold changes")
