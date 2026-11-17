"""Copy redistribution notices for libXray and its Go modules."""
import json
from pathlib import Path
import shutil
import subprocess
import sys

destination = Path(sys.argv[1])
modules = json.loads(subprocess.check_output(["go", "mod", "download", "-json"], text=True)
                     .strip().replace("\n}\n{", "\n},\n{").join(("[", "]")))
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
