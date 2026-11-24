"""Verify the packaged checkout removal and the retained backup entry points."""

import argparse
from hashlib import file_digest
import json
from pathlib import Path
import re
import subprocess


REMOVED_CLASSES = (
    "CheckoutFlowActivity", "ManageDonationsFragment", "PayPalConfirmationDialogFragment",
    "Stripe3DSDialogFragment", "DonationErrorNotifications", "DonationWebviewFragmentBinding",
    "SetCurrencyFragmentBinding", "DonationPillToggleBinding", "PaypalButtonBinding",
)
RETAINED_CLASSES = (
    "MessageBackupsCheckoutActivity", "BackupPaymentErrorHandler", "DonationPermitsRepository",
)


def verify(analyzer, apk, manifest):
    dex = subprocess.check_output([analyzer, "dex", "packages", "--defined-only", apk], text=True)
    files = subprocess.check_output([analyzer, "files", "list", apk], text=True).splitlines()
    classes = {line.split()[-1].rsplit(".", 1)[-1].split("$", 1)[0]
               for line in dex.splitlines() if line.startswith("C ")}
    bad_classes = classes.intersection(REMOVED_CLASSES)
    missing = set(RETAINED_CLASSES).difference(classes)
    resource_paths = set()
    for entry in manifest["removed"]:
        path = entry["path"]
        if "/src/main/res/" in path:
            kind, name = path.split("/src/main/res/", 1)[1].split("/")
            if not kind.startswith("values"):
                resource_paths.add((kind.split("-", 1)[0], name))
    bad_files = []
    for path in files:
        match = re.fullmatch(r"/res/([^/]+)/([^/]+)", path)
        if match and (match[1].split("-", 1)[0], match[2]) in resource_paths:
            bad_files.append(path)
    if bad_classes or missing or bad_files:
        raise ValueError(f"checkout classes present: {sorted(bad_classes)}; missing backup classes: {sorted(missing)}; "
                         f"checkout resources present: {sorted(bad_files)}")
    with apk.open("rb") as handle:
        digest = file_digest(handle, "sha256").hexdigest()
    return {"apk_sha256": digest, "checked_resource_paths": len(resource_paths), "retained_classes": list(RETAINED_CLASSES)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apkanalyzer", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    try:
        result = verify(args.apkanalyzer, args.apk, json.loads(args.manifest.read_text(encoding="utf-8")))
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Donation APK validation failed: {error}\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
