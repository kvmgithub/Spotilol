# MySpotilol M1 – Übergabe 4 an ChatGPT/Codex (R7 nach Review 3)

Stand: 13. November 2026. Antwort auf „MySpotilol – M1, unabhängiges Review 3“ (`M1-07-review-3.md`). Auftrag dieser Runde: R7 samt Regressionstest und die Dokumentberichtigung aus Abschnitt 4. Rollen unverändert.

**Ergebnis:** R7 ist behoben: höchstens vier wartende Zeilen insgesamt, Verdrängung nach fester Regel, Verlust gezählt. @@CIRESULT@@ Offen bleibt HKs Gerätetest. Nichts gemergt, nichts veröffentlicht, `myspoti-ui` unberührt.

## 1. Stände

| | |
| --- | --- |
| Ausgangspunkt | Spitze `4238b13` (Code `5071fce`, Dokumentcommits `de64662`, `4238b13`); unverändert seit Review 3 |
| **Code-Ergebnis** | **`@@RESULT@@`** („Gain diagnostics: at most four lines wait, the rest counted (review 3, R7)“) |
| Geänderte Dateien | `GainDiagnostics.kt`, `GainDiagnosticsTest.kt`; sonst nichts (keine Änderung an Bridge, Relay, Player) |
| Dokumentcommits danach | `@@DOCS@@`: nur `docs/agent-handoff/` (Review 3 abgelegt, diese Übergabe) |
| Diff R7 | https://github.com/kvmgithub/Spotilol/compare/5071fce001759dc6e712cf2a74d8e60fd5b1940e...@@RESULT@@ |
| Diff gegen Basis | https://github.com/kvmgithub/Spotilol/compare/64f97bb9ffe517b2f40ded45b7e934e23ec6a11a...@@RESULT@@ |
| Produktbranch | `myspoti-ui` weiter `63bff95`, keine Überschneidung |

## 2. R7 – begrenzter Ausgabepuffer

**Regel** (KDoc von `GainDiagnostics` und `emit`):

- Höchstens `MAX_WAITING = 4` wartende Zeilen, über alle Arten und Seiten zusammen. Die Zahl der Seiten erhöht diese Grenze nicht.
- Je Schlüssel (Art und Seite: `meta`, `page:<Tag>`, `answer:<Tag>`) wartet wie bisher nur die neueste Zeile; ersetzte zählen in ihrem `merged`. Zeilen verschiedener Seiten werden nie zusammengefasst.
- Kommt ein neuer Schlüssel hinzu, während schon vier warten, fällt die älteste wartende Zeile, die keine Startzeile ist. Sie und alle in ihr zusammengefassten Ereignisse werden in `dropped` gezählt. Die neueste Zeile wartet immer: die Messung der aktuellen Seite bleibt.
- `dropped` steht als Laufzähler am Ende jeder Zeile (nach `merged`), wird bei Aus/Ein und Laufwechsel mit allem anderen zurückgesetzt.
- Bilanz: Jedes angenommene Ereignis wird geschrieben, ist im `merged` einer späteren Zeile derselben Seite enthalten oder in `dropped` gezählt.
- Unverändert: höchstens zwei Zeilen je Sekunde; `page=` beim Annehmen festgelegt; Aus/Ein und Laufwechsel leeren den Puffer und brechen den Flush ab; keine Netzwerk- oder Wiedergabewirkung.

**Regressionstest** (`GainDiagnosticsTest`, „many reloads keep the waiting lines bounded, every event written or counted, each line with its own page“), mit virtueller Uhr und tatsächlich ausgeführten Flush-Timern, nach dem Ablauf des Reviews:

- 1.000 Seiten im Abstand von 500 ms, je eine Seitenmeldung und eine Track-Playback-Antwort auf ein Ticket dieser Seite, danach `pageGone()`: doppelt so viele Ereignisse, wie zwei Zeilen je Sekunde schreiben können.
- Höchststand der wartenden Zeilen über den ganzen Ablauf ≤ 4.
- Nach 10 s ohne neue Ereignisse: Puffer leer, kein Timer offen.
- Bilanz exakt: Startzeile + 2.000 Ereignisse = Σ(1 + `merged`) über alle geschriebenen Zeilen + `dropped` der letzten Zeile; `dropped` > 0.
- Herkunft: Jede Seitenzeile trägt `el` gleich ihrem `page`-Tag, jede Antwortzeile `ans` gleich ihrem `page`-Tag (je Seite ein Request); die Zeilen der letzten Seite erscheinen.
- Rate: in keinem Fenster von 1.000 ms mehr als zwei Zeilen (geprüft an den Schreibzeitpunkten).
- Abschalten mit wartender Zeile: Puffer leer, kein Timer, in den folgenden 5 s keine Zeile.

Weiter grün und unverändert: Herkunft über Reload (Test 16), Laufwechsel, Aus/Ein, Flut gleicher Art (`merged=998`), getrennte Warteschlangen für Antworten und Seitenmeldungen, Playersuche (JS), Relay-Vergleich (`RelayAnswersTest`), Befehlsvergleich.

## 3. Berichtigung zur Lebensdauer der Kennungen (Review 3, Abschnitt 4)

Diese Fassung ersetzt die Aussagen in Übergabe 3, Abschnitte 2.2 und 5:

- **Reload der Seite im selben Prozess:** neuer `page`-Tag, derselbe `run`, solange der Messlauf nicht wechselt. `pl` und `el` der Bridge dürfen wieder bei 1 beginnen. Die App in den Hintergrund zu schicken, garantiert keinen Reload.
- **Abmelden oder Sitzungswechsel im laufenden Prozess:** neuer Messlauf (`run` steigt, Startzeile `reason=account`); Kandidaten und wartende Zeilen des alten Laufs sind verworfen. Getrennt vom Reload zu prüfen.
- **Neustart des App-Prozesses:** `run` und `page` können beide wieder bei 1 beginnen. Eine Startzeile markiert den Beginn eines Abschnitts, ist aber keine prozessübergreifend eindeutige Kennung. Aufzeichnungen verschiedener Prozesse über Zeitstempel und Startzeilen auseinanderhalten; eindeutig sind die Kennungen nur innerhalb eines Prozesses.

## 4. Nachweise

- Lokal: `node --test base/api-spotify/bridge-tests/gain-diagnostics.test.mjs` 23/23 (Bridge unverändert); `python3 .github/scripts/test_ui_release_notes.py` bestanden.

| CI | |
| --- | --- |
| Lauf | @@RUN@@ |
| Ergebnis | @@CI@@ |
| Node | @@NODE@@ |
| APK | @@APKART@@ |
| APK SHA-256 | `@@APKSHA@@` |
| Signierer | `@@SIGNER@@` |
| Testberichte | @@REPORTS@@ |

Erwartete JUnit-Fallzahlen: `GainDiagnosticsTest` 34 (einer mehr als in Runde 3), `RelayAnswersTest` 6. Die Zahlen stehen nur in den Testberichten; Artefakte kann ich in dieser Sitzung nicht herunterladen.

## 5. Gerätetest (HK)

Den installierten Stand über Commit `@@RESULTSHORT@@` und die SHA-256 aus Abschnitt 4 festhalten; die Anzeige 1.4.0 unterscheidet die Arbeitszweig-Builds nicht.

1. Diagnose aus, dann an: Titelwechsel, Lautstärke, Pause/Fortsetzen, Hintergrund und Sperrbildschirm, Rückkehr. Wiedergabe und Lautstärke gleich.
2. Gleichen Titel aus Playlist und aus Album starten: `ctx=` der Seitenzeile, `ctx=` und `here=` der Kandidaten.
3. Echter Reload der Seite: neuer `page=`, gleicher `run`. Getrennt davon Ab- und Anmelden: höherer `run`, Startzeile `reason=account`.
4. Abschalten: keine neuen `gain-diag`-Zeilen; ältere bleiben im Export.
5. Export: die `gain-diag`-Zeilen ohne `spotify:`-Kennungen, Datei-IDs, Tokens, URLs. Fehlende Playerfelder dürfen als `absent`, `invalid` oder `error` erscheinen.

Android Auto und Offlinewiedergabe gehören nicht dazu.
