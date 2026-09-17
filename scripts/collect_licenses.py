import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NAME = ROOT.name
LEGAL = re.compile(r"^(license|licence|notice|copying|copyright)", re.I)


def collect():
    archive = ROOT / "target" / (NAME + ".jar")
    if not archive.is_file():
        raise SystemExit("Build the executable JAR first: mvn clean verify")
    inventory = []
    resources = {}
    with zipfile.ZipFile(archive) as executable:
        for path in sorted(executable.namelist()):
            if not path.startswith("BOOT-INF/lib/") or not path.endswith(".jar"):
                continue
            data = executable.read(path)
            filename = Path(path).name
            found = []
            with zipfile.ZipFile(io.BytesIO(data)) as dependency:
                for entry in sorted(dependency.namelist()):
                    basename = Path(entry).name
                    if entry.endswith("/") or basename.endswith(".class") or not LEGAL.match(basename):
                        continue
                    output = filename[:-4] + "/" + entry.replace("/", "__").replace("\\", "__")
                    resources[output] = dependency.read(entry)
                    found.append(output)
            inventory.append({"jar": filename, "sha256": hashlib.sha256(data).hexdigest(), "licenseResources": found})
    return inventory, resources


def main():
    parser = argparse.ArgumentParser(description="Preserve runtime dependency license resources from the executable JAR.")
    parser.add_argument("--check", action="store_true", help="Check the recorded inventory without modifying source files.")
    args = parser.parse_args()
    inventory, resources = collect()
    destination = ROOT / "licenses"
    if args.check:
        recorded = json.loads((destination / "inventory.json").read_text(encoding="utf-8"))
        if recorded != inventory:
            raise SystemExit("Dependency inventory changed. Refresh licenses before release.")
        for path, data in resources.items():
            if (destination / path).read_bytes() != data:
                raise SystemExit("License resource mismatch: " + path)
        print("Runtime license inventory verified:", len(inventory), "JARs.")
        return
    destination.mkdir(exist_ok=True)
    for path, data in resources.items():
        target = destination / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    (destination / "inventory.json").write_text(json.dumps(inventory, indent=2) + "\n", encoding="utf-8")
    lines = ["# Runtime dependency inventory", "", "Generated from unchanged dependency JARs in BOOT-INF/lib. Their licenses remain applicable.", "", "| JAR | License / notice resources present |", "| --- | --- |"]
    for item in inventory:
        lines.append("| " + item["jar"] + " | " + str(len(item["licenseResources"])) + " |")
    lines += ["", "A count of zero means no matching resource was present in that JAR; it does not mean the dependency is unlicensed. Original dependency JARs remain in the executable.", ""]
    (destination / "README.md").write_text("\n".join(lines), encoding="utf-8")
    print("Collected license resources for", len(inventory), "runtime JARs.")


if __name__ == "__main__":
    main()

