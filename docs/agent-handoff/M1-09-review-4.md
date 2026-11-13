# MySpotilol – M1, unabhängiges Review 4

Stand: 13. November 2026. Prüfung von Claudes vierter Übergabe und Commit `18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac`.

**Urteil: R7 ist behoben. Die technische Codeprüfung von M1 ist abgeschlossen; im vereinbarten Reviewumfang bestehen keine offenen Codebefunde mehr. Offen bleiben HKs Gerätetest und die anschließende Integrationsentscheidung.**

Die Korrektur begrenzt die Warteschlange global, zählt verdrängte Ereignisse vollständig und erhält Seitenherkunft und Ausgaberate. Die Dokumentation unterscheidet jetzt korrekt zwischen Seitenreload, Sitzungswechsel und Prozessneustart. Aus diesem Review ergibt sich kein weiterer Implementierungsauftrag vor dem Gerätetest.

Codex hat ausschließlich gelesen und separate Prüfprogramme ausgeführt. Repository-Code, Branches, Releases und GitHub-Kommentare wurden nicht verändert. Es erfolgte kein Merge.

## 1. Abschluss von R7

Geändert wurden ausschließlich `GainDiagnostics.kt` und `GainDiagnosticsTest.kt`. Bridge, Relay und Player sind gegenüber dem bereits geprüften Code unverändert.

| Anforderung aus Review 3 | Ergebnis |
| --- | --- |
| Feste Grenze über alle Seiten und Ausgabearten | **Erfüllt:** `MAX_WAITING = 4`, unabhängig von der Anzahl der Seiten. |
| Definierte Verdrängung | **Erfüllt:** Ein neuer Schlüssel verdrängt bei vollem Puffer die älteste wartende Nicht-Startzeile. Die neueste Beobachtung wird aufgenommen. |
| Vollständige Verlustzählung | **Erfüllt:** `dropped` erhöht sich um die verdrängte Zeile und alle darin bereits zusammengefassten Ereignisse. |
| Seitenherkunft | **Erhalten:** `page` steht beim Erfassen fest; verschiedene Seiten werden nicht zu einer Beobachtung verschmolzen. |
| Höchstens zwei Zeilen pro Sekunde | **Erhalten:** bestehende globale Drosselung unverändert; im Lasttest kontrolliert. |
| Ausschalten und neuer Messlauf | **Erfüllt:** Puffer wird geleert, Flush abgebrochen und Verlustzähler zurückgesetzt. |
| Kein Eingriff in Wiedergabe oder Netzwerk | **Erfüllt für diesen Diff:** Änderungen ausschließlich an Diagnosepuffer, Zähler, Dokumentation und Testzugang. |

Fundstellen: [`emit`, Zeile 351](https://github.com/kvmgithub/Spotilol/blob/18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac/base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt#L351), [`write`, Zeile 391](https://github.com/kvmgithub/Spotilol/blob/18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac/base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt#L391), [`MAX_WAITING`, Zeile 446](https://github.com/kvmgithub/Spotilol/blob/18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac/base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt#L446).

Der neue [Regressionstest](https://github.com/kvmgithub/Spotilol/blob/18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac/base/api-spotify/src/test/kotlin/app/myspoti/spotify/GainDiagnosticsTest.kt#L372) verwendet eine virtuelle Uhr mit tatsächlich ausgeführten Flush-Aufgaben. Er kontrolliert den Höchststand des Puffers, Ereignisbilanz, Herkunft, aktuelle Seite, Ausgaberate sowie Abschalten mit wartenden Zeilen. Der Test läuft im regulären CI-Testlauf.

## 2. Unabhängige Gegenproben

### Lasttest aus Review 3 wiederholt

Das separate Prüfprogramm aus Review 3 wurde gegen die unveränderte neue Produktionsdatei kompiliert. Ablauf weiterhin: je Seite eine gültige Seitenmeldung und eine Track-Playback-Antwort, anschließend 500 ms virtuelle Zeit; dann Seitenwechsel. Fällige Timer werden ausgeführt.

| Seiten | Wartende Zeilen vorher, `5071fce` | Wartende Zeilen jetzt, `18c323f` | Zusätzliche Abbauzeit vorher | Zusätzliche Abbauzeit jetzt |
| --- | ---: | ---: | ---: | ---: |
| 10 | 9 | 2 | 5 s | 1 s |
| 100 | 99 | 2 | 50 s | 1 s |
| 1.000 | 999 | 2 | 500 s | 1 s |

Die Werte zeigen den Puffer nach dem letzten 500-ms-Schritt, nicht dessen zwischenzeitlichen Höchststand. Die Implementierung und der neue Regressionstest begrenzen diesen auf vier Zeilen. Die Ausgabe blieb in der eigenen Gegenprobe bei höchstens zwei Zeilen je gleitendem 1.000-ms-Fenster. Ausschalten verhinderte weitere Ausgabe.

Die beiden verbleibenden Zeilen stammen jeweils von der letzten Seite. Der wachsende Rückstau aus Review 3 ist damit unter derselben Eingabefolge beseitigt. Dies ist ein synthetischer Lasttest, kein auf dem Telefon gemessener Reload-Ablauf.

### Zusammengefasste Ereignisse bei Verdrängung

Eine zusätzliche eigene Probe erzeugt zunächst eine wartende Zeile mit sieben enthaltenen Ereignissen und verdrängt sie durch Meldungen weiterer Seiten. Eine andere zusammengefasste Zeile bleibt bis zur Ausgabe erhalten.

Ergebnis:

```text
angenommene Ereignisse = 20
ausgegebene Zeilen     = 6
Summe merged          = 5
abschließendes dropped = 9
Bilanz                = 6 + 5 + 9 = 20
```

Damit ist auch die Verlustzählung für bereits zusammengefasste, anschließend verworfene Zeilen geprüft. Weitere Gegenproben bestätigen: Ein neuer Messlauf setzt `dropped` zurück; eine wegen der Drosselung wartende Startzeile überlebt den Pufferüberlauf.

Die zuvor geprüfte Seitenzuordnung über Reload besteht weiterhin: `1:track:0.8000`, `1:volume:0.5000`, `2:track:0.9000`, trotz erneut gleicher Player-/Elementnummern. Die Geräteangabe verspäteter Antworten bleibt korrekt als Empfangssnapshot ausgewiesen.

**Für die Auswertung:** `dropped` ist ein kumulativer Zähler des Messlaufs und wird nicht über alle Logzeilen summiert. `merged` gehört zur jeweiligen Ausgabezeile. Die Bilanz gilt für die angenommenen Ausgabeereignisse eines vollständig ausgegebenen Messabschnitts; Aus/Ein und Sitzungswechsel verwerfen den alten Abschnitt absichtlich. Weder Zähler noch fehlende Diagnosezeilen beweisen fehlende Spotify-Daten.

## 3. Code, Tests und APK

| Gegenstand | Verifiziertes Ergebnis |
| --- | --- |
| Vorheriger Code | `5071fce001759dc6e712cf2a74d8e60fd5b1940e` |
| **Neuer Code** | **`18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac`** |
| Korrektur | Ein Codecommit, zwei Code-/Testdateien. Der [Vergleich](https://github.com/kvmgithub/Spotilol/compare/5071fce001759dc6e712cf2a74d8e60fd5b1940e...18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac) enthält zusätzlich die zwei zuvor vorhandenen Dokumentcommits. |
| Arbeitsbranch-Spitze | `2c2af7aa36c1af09865a31412d9e7c25da252677`. Die zwei Commits nach dem Code ändern ausschließlich `M1-07-review-3.md` und `M1-08-uebergabe-4.md` unter `docs/agent-handoff/`. |
| Produktbranch | `myspoti-ui` unverändert bei `63bff9587c7f3de2a258cd8b8278b0c6e64d9994`; M1 nicht integriert. |
| Quellintegrität | Änderungsdateien gegen Git-Blob-Hashes geprüft. Abschließender Archivvergleich: 637 reguläre Quelldateien unverändert, keine zusätzlichen Dateien im Snapshot. |
| CI | [Lauf 43486444478](https://github.com/kvmgithub/Spotilol/actions/runs/43486444478), Head `18c323f`: Build erfolgreich in 4 min 32 s; Veröffentlichung und Produktbranch-Bereinigung übersprungen. |
| JUnit | Originale heruntergeladene Berichte: **455 Tests in 72 Suiten, null Fehler, null Fehlschläge, null übersprungene Tests**. Darunter 34 `GainDiagnosticsTest` und sechs `RelayAnswersTest`. |
| JavaScript | **23/23** in CI und lokal erneut bestanden. |
| Release-Notes-Prüfung | Lokal bestanden. |
| Eigene Kotlin-Prüfungen | Unveränderte Produktionsdatei mit Kotlin 2.4.0 und kotlinx.serialization 1.11.0 separat kompiliert; Last-, Bilanz-, Startzeilen- und Lebenszyklusproben bestanden. |

### APK

[APK-Artefakt](https://github.com/kvmgithub/Spotilol/actions/runs/43486444478/artifacts/14067103966) und [Testberichte](https://github.com/kvmgithub/Spotilol/actions/runs/43486444478/artifacts/14068450731) waren herunterladbar und wurden tatsächlich geprüft.

| Prüfung | Ergebnis |
| --- | --- |
| Datei | `MySpotilol-arm64-1.4.0.apk` |
| Selbst berechnete APK-SHA-256 | `9a1c1b7536d61070772a80073fd41f248e8988451a4884b2ae2eb54c4a7b9bab` |
| Zuordnung | Übereinstimmung mit Übergabe und `SHA256SUMS.txt`; `build-info.txt` nennt Commit `18c323f` und Lauf `43486444478`. Beide ZIP-Prüfsummen stimmen mit den gemeldeten GitHub-Artefakt-Digests überein. |
| Bridge | Eingebettetes `bridge/player-bridge.js` bytegleich mit dem geprüften Quellstand. |
| Signierer | CI-Prüfung und `signers.txt`: ein Signierer, SHA-256 `73cf309a74b1a107a1e1cbb9453806f7b0d8e803957ecd371cb51cfed0b21e9a`. Keine zusätzliche lokale `apksigner`-Prüfung. |
| Metadaten | Paket `com.project.lol.fork.ui`, Version 1.4.0 / Code 1004000; laut geprüften Metadaten nicht debuggable und ohne Installer-Berechtigung. |
| Inhalt | Nur `arm64-v8a`-Bibliotheken. Wiederholter DEX-Scan ohne Treffer für die acht im bestehenden Workflow ausgeschlossenen Komponenten; kein allgemeiner Telemetriefreiheitsnachweis. |

Kein eigener Android-Gerätetest und kein vollständiger lokaler Gradle-/APK-Build. Die vollständigen Kotlin-/Android-Testergebnisse stammen aus dem überprüften CI-Lauf. Reale WebView-Zustellung, hörbare Wiedergabe und gerätespezifisches Hintergrundverhalten sind damit nicht abschließend geprüft.

## 4. Abnahmestatus und nächster Schritt

| Bereich | Status |
| --- | --- |
| Frühere Befunde R1–R6 und Pakete A–C | Erledigt nach den vorherigen Reviews; durch diesen engen Diff nicht erneut geöffnet. |
| R7 – global begrenzte Warteschlange | **Erledigt und unabhängig bestätigt.** |
| Dokumentation der Kennungen | **Berichtigt:** Reload im selben Prozess, Sitzungswechsel und Prozessneustart werden korrekt unterschieden. |
| Automatisierte Prüfungen und Arbeitsbranch-APK | **Bestätigt.** |
| Gerätetest | **Offen.** |
| Merge und regulärer Release | **Nicht durchgeführt; gesonderte Integrationsentscheidung.** |

Für den Gerätetest gilt die APK von Commit `18c323f` mit obiger SHA-256. Die Versionsanzeige 1.4.0 allein unterscheidet die bisherigen Testbuilds nicht.

Der vorhandene Testplan genügt: Diagnose aus/an bei Titelwechsel, Lautstärke, Pause/Fortsetzen, Hintergrund und Sperrbildschirm; denselben Titel aus Playlist und Album starten; echten Seitenreload getrennt von Ab-/Anmeldung prüfen; anschließend Diagnose ausschalten und den Export kontrollieren. Android Auto und Offlinewiedergabe gehören nicht zum Umfang.

Die Prüfung auf Kennungen, Tokens und URLs bezieht sich auf die neuen `gain-diag`-Zeilen. Daraus folgt keine Zusicherung für sämtliche bestehenden Logpfade. Nach einem Prozessneustart können `run` und `page` wieder bei 1 beginnen; getrennte Aufzeichnungen und Zeit-/Startgrenzen erhalten.

**Für Claude:** Keine weitere Codekorrektur aus diesem Review erforderlich. Den geprüften Stand für den Gerätetest beibehalten. Erst ein konkreter neuer Fehlernachweis begründet eine weitere Korrekturrunde. M2 setzt die Auswertung realer Messungen voraus; Merge und reguläre Veröffentlichung bleiben HKs Integrationsentscheidung.
