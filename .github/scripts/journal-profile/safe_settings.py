"""Keep diagnostic samples without recording the runner's environment or property values."""
import pathlib
import sys
import xml.etree.ElementTree as ET

source, output = map(pathlib.Path, sys.argv[1:])
tree = ET.parse(source)
blocked = {"jdk.InitialEnvironmentVariable", "jdk.InitialSystemProperty", "jdk.InitialSecurityProperty"}
allowed = {"jdk.ExecutionSample", "jdk.ObjectAllocationSample", "jdk.ThreadPark", "jdk.DataLoss"}
found = set()
selected = set()
for event in tree.getroot().findall("event"):
    name = event.attrib["name"]
    if name not in allowed:
        setting = next(s for s in event.findall("setting") if s.attrib["name"] == "enabled")
        setting.attrib.pop("control", None)
        setting.text = "false"
        if name in blocked:
            found.add(name)
    else:
        setting = next(s for s in event.findall("setting") if s.attrib["name"] == "enabled")
        setting.attrib.pop("control", None)
        setting.text = "true"
        selected.add(name)
required = {"jdk.InitialEnvironmentVariable", "jdk.InitialSystemProperty"}
if not required.issubset(found):
    raise SystemExit("Required privacy settings are absent: " + repr(required - found))
if selected != allowed:
    raise SystemExit("Required diagnostic events are absent: " + repr(allowed - selected))
for name in blocked - found:
    event = ET.SubElement(tree.getroot(), "event", {"name": name})
    ET.SubElement(event, "setting", {"name": "enabled"}).text = "false"
    print("Explicitly disabling optional event absent from this template:", name)
tree.write(output, encoding="utf-8", xml_declaration=True)
