# Login challenge compatibility

> **For agentic workers:** Use superpowers:executing-plans for this focused correction. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Permit Spotify's login challenge without expanding native authority or exposing challenge identifiers in diagnostics.

**Architecture:** Add the exact HTTPS challenge host to WebSecurityPolicy's navigation allowlist. Leave challenge documents unmodified and retain the existing bridge, native HTTP and cookie boundaries. Log blocked destinations as validated hostnames only.

**Tech Stack:** Android WebView, Kotlin, Java, JUnit, standalone Java/Node/Python checks, GitHub Actions.

**Spec:** The login failure reported for 1.2.1008 and the security invariants in `AGENTS.md`.

## Global constraints

- Keep package `com.project.lol.fork` and the existing signing identity.
- Only exact HTTPS `challenge.spotify.com` navigation is newly permitted.
- No bridge, native fetch, cookie restoration or sniff authority for the challenge host.
- No new permissions, dependencies, telemetry or certificate exceptions.
- Automated checks cannot establish that login works on the reported physical device.

## Evidence and limits

The captured reCAPTCHA iframe executes JavaScript and reports denied Storage Access API permission; this alone does not establish a connection failure. Spotify's current login JavaScript follows server-supplied challenge redirects. The official challenge application lists `/c/[sessionId]/[challengeId]/recaptcha`, email and MFA routes. Version 1.2.1008 rejects that host without logging navigation denials. The supplied logs do not prove that this particular attempt reached the challenge redirect.

## Review focus

- Hostname impersonation, credentials, HTTP and nonstandard ports remain blocked.
- Challenge frames receive no native methods or transport privileges.
- Challenge session identifiers, queries and fragments never enter exported diagnostics.
- Main-frame restrictions must not newly reject CAPTCHA subframes.
- Existing player injection and navigation-token lifetime remain intact.

## Task 1: Navigation compatibility and diagnostics

Files: `WebSecurityPolicy.java`, `LogRedactor.java`, both WebView clients, `MainActivity.kt`, `SettingsScreen.kt`, `tests/security/SecurityPolicyTest.java`, `WebSecurityPolicyTest.java`, README, CHANGELOG and release notes workflow.

- [x] Add failing navigation/authority/redaction tests; run the standalone suite and confirm the challenge-navigation failure.
- [x] Allow the exact challenge host; identify challenge documents centrally and skip app injections there.
- [x] Add hostname-only navigation-denial logging in the main and popup clients; redact challenge paths in existing diagnostics.
- [x] Run standalone checks and inspect the complete diff; obtain an independent code review.
- [x] Document the compatibility correction and its unverified device outcome; commit.

## Task 2: Signed update

- [ ] Publish the reviewed commit through the existing guarded GitHub release workflow.
- [ ] Verify JVM tests, release lint, release assembly, APK identity and signing-certificate comparison.
- [ ] Provide the resulting APK and request device confirmation without claiming the reported login is fixed.
