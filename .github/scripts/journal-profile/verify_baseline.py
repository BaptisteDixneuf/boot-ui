"""Temporary profile guard: exact prior baseline runtime entries and external jars."""
import hashlib
import io
import json
import pathlib
import sys
import zipfile

jar, expected_path, output = map(pathlib.Path, sys.argv[1:])
expected = json.loads(expected_path.read_text())
external, libraries, application = {}, {}, {}
with zipfile.ZipFile(jar) as package:
    for name in package.namelist():
        if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
            content = package.read(name)
            key = name.rsplit("/", 1)[-1]
            if key.startswith("bootui-"):
                with zipfile.ZipFile(io.BytesIO(content)) as nested:
                    libraries[key] = {n: hashlib.sha256(nested.read(n)).hexdigest()
                                      for n in nested.namelist()
                                      if not n.endswith("/") and not n.startswith("META-INF/maven/")
                                      and n != "META-INF/MANIFEST.MF"}
            else:
                external[key] = hashlib.sha256(content).hexdigest()
        elif name.startswith("BOOT-INF/classes/") and not name.endswith("/"):
            application[name] = hashlib.sha256(package.read(name)).hexdigest()
actual = {"external": external, "bootuiClasses": libraries, "application": application}
if actual != expected:
    output.write_text(json.dumps({"actual": actual, "expected": expected, "matches": False}, indent=2) + "\n")
    raise SystemExit("Baseline runtime bytes differ from the previously verified c928 package")
output.write_text(json.dumps({"runtime": actual, "matches": True,
                              "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest()}, indent=2) + "\n")
