#!/usr/bin/env python3
"""Prepare and verify an isolated device-test build of the upstream contribution."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

SOURCE_COMMIT = "24f2d3b4a71a45311283e22762297e18948f1c25"
ORIGINAL_URL = "https://github.com/lyssadev/Spotilol/releases/download/1.1.8/app-release.apk"
ORIGINAL_SHA256 = "4161b817758a76103df58635846e40d44984ddb75d533a6881e44a3b4e1d01e5"
PACKAGE = "com.project.lol.prtest"
LABEL = "Spotilol PR-Test"
FIREBASE_KEYS = {
    "google_app_id", "gcm_defaultSenderId", "google_api_key",
    "google_storage_bucket", "firebase_database_url", "project_id", "default_web_client_id",
}

def command(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout

def aapt():
    return os.environ.get("PR_TEST_AAPT") or str(
        Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0/aapt")

def firebase_values(apk):
    output = command(aapt(), "dump", "--values", "resources", str(apk))
    current = None
    values = {}
    for line in output.splitlines():
        match = re.search(r"\b(?:string/|string:)([A-Za-z0-9_]+)", line)
        if match:
            current = match.group(1)
        if current in FIREBASE_KEYS:
            match = re.search(r'\(string8?\)\s+(".*")\s*$', line)
            if match:
                values[current] = json.loads(match.group(1))
                current = None
    required = {"google_app_id", "gcm_defaultSenderId", "google_api_key", "project_id"}
    assert required <= values.keys(), "Original Firebase resources are incomplete"
    assert values["google_app_id"].startswith("1:" + values["gcm_defaultSenderId"] + ":android:")
    return values

def original_apk():
    target = Path(os.environ["RUNNER_TEMP"]) / "upstream-1.1.8.apk"
    if not target.exists():
        with urllib.request.urlopen(ORIGINAL_URL, timeout=60) as response:
            target.write_bytes(response.read())
    assert hashlib.sha256(target.read_bytes()).hexdigest() == ORIGINAL_SHA256, "Original APK changed"
    return target

def replace_once(text, old, new):
    assert text.count(old) == 1, "Unexpected build configuration"
    return text.replace(old, new, 1)

def prepare():
    changed = command("git", "diff", "--name-only", SOURCE_COMMIT, "--", ".", ":!.github").strip()
    assert not changed, "Production source differs from the pull request"
    values = firebase_values(original_apk())
    project = {"project_number": values["gcm_defaultSenderId"], "project_id": values["project_id"]}
    if "google_storage_bucket" in values:
        project["storage_bucket"] = values["google_storage_bucket"]
    if "firebase_database_url" in values:
        project["firebase_url"] = values["firebase_database_url"]
    client = {
        "client_info": {"mobilesdk_app_id": values["google_app_id"],
                        "android_client_info": {"package_name": PACKAGE}},
        "oauth_client": [], "api_key": [{"current_key": values["google_api_key"]}],
        "services": {"appinvite_service": {"other_platform_oauth_client": []}},
    }
    if "default_web_client_id" in values:
        client["oauth_client"].append({"client_id": values["default_web_client_id"], "client_type": 3})
    config = Path("app/google-services.json")
    config.write_text(json.dumps({"project_info": project, "client": [client],
                                 "configuration_version": "1"}, indent=2) + "\n")
    config.chmod(0o600)
    run_number = int(os.environ["GITHUB_RUN_NUMBER"])
    version_code, version_name = 1000 + run_number, f"1.1.8-pr103.{run_number}"
    gradle = Path("app/build.gradle.kts")
    text = gradle.read_text()
    for old, new in (
        ('applicationId = "com.project.lol"', f'applicationId = "{PACKAGE}"'),
        ("versionCode = 18", f"versionCode = {version_code}"),
        ('versionName = "1.1.8"', f'versionName = "{version_name}"'),
        ('listOf("arm64-v8a", "armeabi-v7a")', 'listOf("arm64-v8a")'),
        ('storeFile = rootProject.file("keystore/' + '$' + '{keystoreProperties.getProperty("storeFile")}")',
         'storeFile = file(System.getenv("APK_KEYSTORE"))'),
        ('storePassword = keystoreProperties.getProperty("storePassword")',
         'storePassword = System.getenv("APK_KEY_PASSWORD")'),
        ('keyAlias = keystoreProperties.getProperty("keyAlias")',
         'keyAlias = System.getenv("APK_KEY_ALIAS")'),
        ('keyPassword = keystoreProperties.getProperty("keyPassword")',
         'keyPassword = System.getenv("APK_KEY_PASSWORD")'),
    ):
        text = replace_once(text, old, new)
    for dependency in ("firebase-analytics", "firebase-crashlytics", "firebase-perf"):
        assert f'implementation("com.google.firebase:{dependency}")' in text
    gradle.write_text(text)
    manifest = Path("app/src/main/AndroidManifest.xml")
    xml = manifest.read_text()
    assert "firebase_analytics_collection_deactivated" not in xml
    assert "firebase_performance_collection_deactivated" not in xml
    manifest.write_text(replace_once(xml, 'android:label="@string/app_name"', f'android:label="{LABEL}"'))
    with open(os.environ["GITHUB_ENV"], "a") as env:
        env.write(f"APK_VERSION_CODE={version_code}\nAPK_VERSION_NAME={version_name}\n")
    print("Original Firebase configuration restored; isolated ARM64 test packaging prepared.")

def verify():
    version = os.environ["APK_VERSION_NAME"]
    output = Path("release-assets")
    output.mkdir(exist_ok=True)
    apk = output / f"Spotilol-PR103-arm64-{version}.apk"
    apk.write_bytes(Path("app/build/outputs/apk/release/app-release.apk").read_bytes())
    metadata = command(aapt(), "dump", "badging", str(apk))
    package_line = next(line for line in metadata.splitlines() if line.startswith("package:"))
    fields = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    assert fields["name"] == PACKAGE
    assert fields["versionCode"] == os.environ["APK_VERSION_CODE"]
    assert fields["versionName"] == version
    assert "application-debuggable" not in metadata
    assert f"application-label:'{LABEL}'" in metadata
    with zipfile.ZipFile(apk) as archive:
        abis = {name.split("/")[1] for name in archive.namelist()
                if name.startswith("lib/") and name.endswith(".so")}
        assert abis == {"arm64-v8a"}, abis
    manifest = command(aapt(), "dump", "xmltree", str(apk), "AndroidManifest.xml")
    for component in ("FirebaseInitProvider", "CrashlyticsRegistrar",
                      "FirebasePerfRegistrar", "AnalyticsConnectorRegistrar"):
        assert component in manifest, f"Missing Firebase component: {component}"
    assert firebase_values(apk) == firebase_values(original_apk()), "Firebase configuration changed"
    reports = [ET.parse(path).getroot() for path in
               Path("app/build/test-results/testDebugUnitTest").glob("TEST-*.xml")]
    tests = sum(int(report.attrib["tests"]) for report in reports)
    failures = sum(int(report.attrib.get("failures", 0)) + int(report.attrib.get("errors", 0))
                   for report in reports)
    assert tests == 14 and failures == 0, (tests, failures)
    lint = ET.parse("app/build/reports/lint-results-release.xml").getroot()
    lint_errors = [issue for issue in lint.findall("issue")
                   if issue.attrib.get("severity", "").lower() in ("fatal", "error")]
    assert not lint_errors, "Release lint reported errors"
    signature = command(str(Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0/apksigner"),
                        "verify", "--verbose", "--print-certs", str(apk))
    digest = next(line.split(": ", 1)[1] for line in signature.splitlines()
                  if line.startswith("Signer #1 certificate SHA-256 digest:"))
    sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()
    (output / "SHA256SUMS.txt").write_text(f"{sha256}  {apk.name}\n")
    record = {
        "source_commit": SOURCE_COMMIT, "build_commit": os.environ["GITHUB_SHA"],
        "package": PACKAGE, "label": LABEL, "version": version,
        "version_code": int(os.environ["APK_VERSION_CODE"]), "abis": sorted(abis),
        "apk_sha256": sha256, "signing_certificate_sha256": digest,
        "firebase": "Upstream Analytics, Crashlytics and Performance retained",
        "firebase_configuration_source": ORIGINAL_URL, "original_apk_sha256": ORIGINAL_SHA256,
        "packaging_changes": ["application ID", "label", "version", "ARM64 only", "test signing"],
        "crashlytics_mapping_upload": "Skipped for this test build",
        "android_jvm_tests": tests, "android_jvm_failures": failures,
        "release_lint_errors": len(lint_errors),
        "device_validation": "Proxy mode and Android Auto pending",
    }
    (output / "TEST_BUILD.json").write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps(record, indent=2))
    print(signature)

if __name__ == "__main__":
    {"prepare": prepare, "verify": verify}[sys.argv[1]]()
