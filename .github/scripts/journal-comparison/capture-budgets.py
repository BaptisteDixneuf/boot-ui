"""Run the existing opt-in capture class unchanged; retain real failures and its report limitations."""
import hashlib
import json
import os
import pathlib
import shlex
import signal
import shutil
import subprocess
import xml.etree.ElementTree as ET

output = pathlib.Path("comparison/evidence/capture")
output.mkdir(parents=True, exist_ok=True)
workspace = pathlib.Path.cwd()
temporary = pathlib.Path(os.environ["RUNNER_TEMP"])
arms = [("baseline", temporary / "journal-baseline", temporary / "baseline-m2"),
        ("candidate", workspace, temporary / "candidate-m2")]
results = []
environment = os.environ.copy()
environment["MAVEN_OPTS"] = environment.get("MAVEN_OPTS", "") + " -XX:ActiveProcessorCount=4 -Xmx768m"
for name, checkout, repository in arms:
    destination = output / name
    destination.mkdir(exist_ok=True)
    command = ["./mvnw", "-B", "-ntp", "-Dmaven.repo.local=" + str(repository),
               "-pl", "bootui-engine", "test", "-Dtest=JournalCaptureBudgetBenchmarkTest",
               "-Dbootui.benchmark=true", "-DargLine=-XX:ActiveProcessorCount=4 -Xmx768m"]
    source = checkout / ("bootui-engine/src/test/java/io/github/jdubois/bootui/engine/journal/"
                         "JournalCaptureBudgetBenchmarkTest.java")
    row = {"arm": name, "command": command, "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest()}
    report = checkout / "bootui-engine/target/capture-budgets/journal.md"
    xml = checkout / ("bootui-engine/target/surefire-reports/"
                      "TEST-io.github.jdubois.bootui.engine.journal.JournalCaptureBudgetBenchmarkTest.xml")
    for existing in [report, xml]:
        if existing.is_file():
            preexisting = destination / "preexisting"
            preexisting.mkdir(exist_ok=True)
            shutil.move(str(existing), str(preexisting / existing.name))
    with (destination / "command.log").open("w") as log:
        process = subprocess.Popen(command, cwd=checkout, env=environment, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try:
            row["exitCode"] = process.wait(timeout=180)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=10)
            row["exitCode"] = 124
            row["failure"] = "Existing capture class exceeded its 180-second command bound"
    if report.is_file():
        shutil.copyfile(report, destination / "journal.md")
        row["reportSha256"] = hashlib.sha256(report.read_bytes()).hexdigest()
    else:
        row["failure"] = "Original capture report is missing"
    if xml.is_file():
        shutil.copyfile(xml, destination / "test.xml")
        suite = ET.parse(xml).getroot()
        props = {p.attrib["name"]: p.attrib["value"] for p in suite.findall("./properties/property")}
        row["xml"] = {k: int(suite.attrib[k]) for k in ["tests", "errors", "failures", "skipped"]}
        row["actualJvmCommand"] = props.get("sun.java.command", "")
        row["java"] = props.get("java.version", "")
        row["actualXmlProperties"] = {name: props.get(name) for name in [
            "argLine", "bootui.benchmark", "java.version", "sun.java.command"]}
        arguments = shlex.split(props.get("argLine", ""))
        cpu = [argument for argument in arguments if argument.startswith("-XX:ActiveProcessorCount=")]
        heap = [argument for argument in arguments if argument.startswith("-Xmx")]
        cases = suite.findall("testcase")
        row["actualCpuAndHeapCaps"] = cpu == ["-XX:ActiveProcessorCount=4"] and heap == ["-Xmx768m"]
        row["exactExecutedCase"] = (len(cases) == 1
                                   and cases[0].attrib.get("name") == "measuresTheJournalCaptureBudgets"
                                   and not cases[0].findall("skipped"))
        row["oneClassOneFork"] = "jvmRun1" in row["actualJvmCommand"] and "jvmRun2" not in row["actualJvmCommand"]
        row["originalAssertionsPassed"] = (row["exitCode"] == 0 and row["xml"]["tests"] == 1
                                          and not any(row["xml"][k] for k in ["errors", "failures", "skipped"])
                                          and row["oneClassOneFork"] and row["java"].startswith("17.")
                                          and row["actualCpuAndHeapCaps"] and row["exactExecutedCase"]
                                          and props.get("bootui.benchmark") == "true"
                                          and report.is_file())
    else:
        row["failure"] = "Original capture XML is missing"
        row["originalAssertionsPassed"] = False
    results.append(row)
    (output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
same_source = len(results) == 2 and results[0]["sourceSha256"] == results[1]["sourceSha256"]
summary = {"arms": results, "classSourceIdentical": same_source,
           "originalAssertionsPassedBothArms": same_source and all(r["originalAssertionsPassed"] for r in results),
           "qualification": "Unpaced latency is per attempted offer and omits drop counts; paced report is rounded and asserts zero drops, not strict >=20,000/s. No rewritten thresholds."}
(output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary, indent=2))
