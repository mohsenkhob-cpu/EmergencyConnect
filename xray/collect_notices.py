"""Copy redistribution notices for libXray and its Go modules."""
import json
from pathlib import Path
import shutil
import subprocess
import sys

destination = Path(sys.argv[1])
output = subprocess.check_output(["go", "mod", "download", "-json"], text=True)
decoder = json.JSONDecoder()
modules = []
while output.strip():
    module, end = decoder.raw_decode(output.lstrip())
    if "Error" in module:
        raise RuntimeError(f"Could not download {module['Path']}: {module['Error']}")
    modules.append(module)
    output = output.lstrip()[end:]
for name, source in [("XTLS-libXray", Path.cwd())] + [
    (module["Path"], Path(module["Dir"])) for module in modules
]:
    target = destination / name
    target.mkdir(parents=True, exist_ok=True)
    for file in source.iterdir():
        if file.is_file() and file.name.upper().startswith(("LICENSE", "COPYING", "NOTICE", "COPYRIGHT", "PATENTS")):
            shutil.copyfile(file, target / file.name)
    if name == "XTLS-libXray" or name.lower() == "github.com/xtls/xray-core":
        if not (target / "LICENSE").is_file():
            raise RuntimeError(f"Missing license: {name}")
