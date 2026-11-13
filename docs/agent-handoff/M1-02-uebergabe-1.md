# MySpotilol M1 – Übergabe an ChatGPT/Codex

Stand: 13. November 2026. Antwort auf „MySpotilol – Umsetzungsauftrag für Claude Opus 5.5“, Arbeitspaket M1 (Gain-Diagnose).

**Ergebnis:** M1 ist umgesetzt, die Tests sind grün und die APK ist signiert und geprüft. Offen ist nur der Gerätetest durch HK. Nichts wurde gemergt, nichts veröffentlicht und `myspoti-ui` nicht berührt.

## 1. Stände

| | |
| --- | --- |
| Basis | `64f97bb9ffe517b2f40ded45b7e934e23ec6a11a` (`myspoti-ui`, MySpotilol 1.4.0). Unverändert seit der Prüfung vom 12.11., keine Abweichung. |
| Arbeitszweig | `work/gain-diagnostics`. Neu angelegt, es gab keinen Vorgänger. |
| Ergebnis | `82efcaa3eb1de21be85fc7dd8b6016a8eff260cd` |
| Diff | https://github.com/kvmgithub/Spotilol/compare/64f97bb9ffe517b2f40ded45b7e934e23ec6a11a...82efcaa3eb1de21be85fc7dd8b6016a8eff260cd |

Commits:

| Commit | Inhalt |
| --- | --- |
| `8c5b365` | Tests zuerst, Gerüst ohne Verhalten, CI für Arbeitszweige |
| `b1f7adf` | Testkorrekturen: Testname mit `:` (von der JVM abgelehnt), Ersatz-Player ohne Lautstärkefeld |
| `f55f6f9` | Implementierung |
| `82efcaa` | Spieler-Vergleichstest deterministisch gemacht; Meldungen fehlgeschlagener Tests im Job-Log |

Repository-Anweisungen: Es gibt weder `CLAUDE.md` noch `AGENTS.md` noch Beitragsrichtlinien im Repository. Der Arbeitsbaum war sauber.

## 2. Geänderte Dateien

| Datei | Zweck |
| --- | --- |
| `base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt` (neu) | Der Diagnosebaustein. Liest `gain_db` aus Relay-Antworten und die Beobachtungen der Seite. Er korreliert über Mess-IDs je Lauf und hält höchstens 256 Zuordnungen (LRU). Er schreibt höchstens 2 Zeilen pro Sekunde; je Art wartet nur eine Zeile. Uhr, Timer und Ausgabe sind injizierbar. Er hat keinen Netzwerkzugriff und keinen Zugriff auf Befehle. |
| `.../spotify/SpotifyPlayer.kt` | Instanz `gainDiagnostics` mit Zugriff auf bereits vorhandene Werte: Lautheits-Cache, Normalisierung, Boost, Crossfade, Werbe-Stummschaltung, `lastMedia.active`. Dazu `setGainDiagnostics(on)` und ein Neusenden von `diag.gain` bei jedem `ready`, solange der Schalter an ist. |
| `.../spotify/SpotifyApi.kt` | `newSession()` (Logout, Sitzungsverlust) startet einen neuen Diagnoselauf. |
| `.../spotify/bridge/Bridge.kt` | `type: "diag"` wird vor jedem Logging abgezweigt und an `diag` übergeben. Diese Nachrichten werden nie roh geloggt und nie als Player-Ereignis weitergereicht. Ein Fehler des Lesers wird ohne Nachrichteninhalt gemeldet. |
| `.../resources/bridge/player-bridge.js` | Passive Beobachtung über `diag.gain {on}`: ein `volumechange`-Listener am aktiven Element, Meldungen bei Titel- und Elementwechsel, höchstens 2 Meldungen pro Sekunde mit Zusammenfassung. `gdWrote()` markiert eigene Pegelschreibungen an den drei bestehenden Stellen `setLevel`, `restoreOwn` und `media.mute`. Ihre Logik bleibt unverändert. |
| `base/host-android/.../ControlRelay.kt` | Merkt sich beim Senden das Lauf-Token (`null`, solange die Diagnose aus ist). Nach `answer(...)` liest es die bereits weitergegebene `track-playback`-Antwort (`sent`), und nur 2xx. |
| `base/host-android/.../MyspotiRuntime.kt` | Getter `gainDiagnostics`; `null`, solange die Sitzung noch nicht existiert. |
| `base/app/.../data/BetaLogs.kt` | `applyToPlayer()`: Der vorhandene Diagnoseschalter wird bis zum Sammler durchgereicht, auch bei `setEnabled`. |
| `base/app/.../di/Graph.kt` | Ruft beim Start `BetaLogs.applyToPlayer()` auf. |
| `base/api-spotify/src/test/.../GainDiagnosticsTest.kt` (neu) | 20 Verhaltenstests, einschließlich des Vergleichs Spieler an/aus. |
| `base/api-spotify/src/test/.../bridge/BridgeMessagesTest.kt` | 2 Fälle: `diag`-Abzweig und ein Leser, der wirft. |
| `base/api-spotify/bridge-tests/gain-diagnostics.test.mjs` (neu) | 10 Tests. Sie laden das echte `player-bridge.js` in `node:vm`, mit Ersatz-Web-Player, Ersatz-Medienelementen und virtueller Uhr. |
| `.github/workflows/spotilol-ui.yml` | Node-Bridge-Tests als eigener Schritt; Gradle läuft danach auch, wenn sie fehlschlagen. Für Arbeitszweige: Artefakte (APK, Prüfsumme, Signaturausgabe, `aapt`-Metadaten, Testberichte, Fehlerlog) über `actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9` (v7.0.2). Den Branch `ui-ci-logs` schreibt nur noch `myspoti-ui`. Neu im Fehlerlog: fehlgeschlagene Tests mit Meldung. Gradle-Log mit `tee -a`, damit die Node-Ausgabe erhalten bleibt. |
| `CHANGELOG-UI.md` | Eintrag unter `[Unreleased]`, keine Versionsänderung. |

### Abweichungen vom Auftrag

1. **Übergabe nicht unter `docs/agent-handoff/` im Repository.** Das ist HKs ausdrückliche Regel: keine KI-Artefakte im Repository, also keine Agent-Dateien, keine KI-Hinweise und keine Claude-Commit-Zeilen. Auftrag und Ergebnis gehen deshalb als Dateien an HK. Die Commits tragen keine Co-Author-Zeile.
2. **Kein `selected`.** Weder Relay noch Bridge haben eine eindeutige Quelle dafür, welche Datei die Seite abspielt. Format 10 und 12 haben in den Antworten dieselbe Bitrate, und `storage-resolve` läuft nicht über das Relay. Jede angebotene Datei ist deshalb `candidate`, ohne Datei-ID `unknown`.
3. **Die Mute-Wächter des Document-Start-Skripts** (`__mspGuard` in `ControlRelay.SCRIPT`) sind nicht markiert. Sie setzen das Element während der Werbung auf 0,001. Im Log ist das an `pageAdMute=true` und `vol=0.0010` erkennbar. Für die Markierung hätte das Relay-Skript geändert werden müssen.
4. **Der Kontext einer Antwortzeile** stammt vom laut Antwort aktuellen Eintrag, ersatzweise vom ersten Titel. Seitenzeilen tragen den Kontext der Seite.
5. **Gradle konnte in dieser Sitzung nicht lokal laufen.** Maven Central, Google Maven und `services.gradle.org` sind vom Proxy gesperrt. Alle Gradle-Befehle liefen deshalb nur in der CI. Die Node-Tests und der Python-Test liefen lokal und in der CI.
6. **Ein zusätzlicher Spieler-Test im selben Testfile:** der Vergleich der Wiedergabebefehle bei Diagnose an und aus über `SpotifyPlayer` mit Ersatz-Host. Er stand nicht als Pflichtfall im Auftrag, belegt aber die Abnahme „Kein Wiedergabeeingriff“.
7. **Zeilenlimit auf beiden Seiten.** Die Bridge sendet höchstens 2 Meldungen pro Sekunde; Kotlin schreibt höchstens 2 Zeilen pro Sekunde insgesamt. Je Art (`meta`, `answer`, `page`) wartet höchstens eine Zeile, die neueste. `merged` zählt, wie viele frühere sie ersetzt hat; `pageMerged` ist die Zahl der Bridge.

## 3. Befehle und Ergebnisse

Lokal am Ergebnisstand:

```
python3 .github/scripts/test_ui_release_notes.py
  -> release notes checks passed
node --test base/api-spotify/bridge-tests/gain-diagnostics.test.mjs
  -> tests 10, pass 10, fail 0   (Node v22.23.4)
```

Rot-Nachweis vor der Implementierung:

| Lauf | Commit | Ergebnis |
| --- | --- | --- |
| [43353551195](https://github.com/kvmgithub/Spotilol/actions/runs/43353551195) | `8c5b365` | Rot, aber wegen eines Testnamens mit `:` (Kompilierfehler, kein Verhaltensnachweis). Node: 9 von 10 rot. |
| [43354085872](https://github.com/kvmgithub/Spotilol/actions/runs/43354085872) | `b1f7adf` | **Verhaltens-Rot.** Gradle: 241 Tests, davon 21 rot: alle 19 `GainDiagnosticsTest` außer „off by default“ und die 2 neuen `BridgeMessagesTest`-Fälle. Node: 9 von 10 rot, Grund `unknown method diag.gain`. Lokal am Gerüst: ebenfalls 9 von 10 rot. |

Nach der Implementierung:

| Lauf | Commit | Ergebnis |
| --- | --- | --- |
| [43354702399](https://github.com/kvmgithub/Spotilol/actions/runs/43354702399) | `f55f6f9` | Rot: 1 von 241, der Spieler-Vergleichstest. Ursache war der Testablauf: `next()` mit Fade und verzögertem Wiederanstieg, dessen Timer vor oder nach dem 1-s-Ende der Aufzeichnung landen konnte. Behoben in `82efcaa`: Der Test endet jetzt auf einer Pause, die die Seite bestätigt. Die Diagnose sendet weiterhin keinen Wiedergabebefehl; der Test vergleicht alle Nicht-Lese-Aufrufe sortiert. |
| **[43355426911](https://github.com/kvmgithub/Spotilol/actions/runs/43355426911)** | **`82efcaa`** | **Grün** (`workflow_dispatch` auf `work/gain-diagnostics`): `test_ui_release_notes.py` bestanden; Node 10 von 10; `./gradlew testDebugUnitTest :api:test :api-spotify:test :app:assembleRelease` „BUILD SUCCESSFUL in 4m 44s“, ohne fehlgeschlagenen Test; APK-Prüfung bestanden. |

Der Workflow startet bei einem normalen Push nur auf `myspoti-ui`. Alle Läufe oben sind `workflow_dispatch` auf dem Arbeitszweig.

Den gezielten Befehl `./gradlew :api-spotify:test --tests app.myspoti.spotify.GainDiagnosticsTest` deckt der volle `:api-spotify:test` mit ab. Ein eigener CI-Aufruf dafür wurde nicht ergänzt.

## 4. APK

| | |
| --- | --- |
| Artefakt | `apk-82efcaa3eb1de21be85fc7dd8b6016a8eff260cd`, ID 14005504241, 4.803.807 Bytes. Enthält APK, `SHA256SUMS.txt`, `signers.txt`, `apk-metadata.txt` und `build-info.txt`. https://github.com/kvmgithub/Spotilol/actions/runs/43355426911/artifacts/14005504241 |
| Datei | `MySpotilol-arm64-1.4.0.apk`. Die Version folgt `CHANGELOG-UI.md`; es gibt keinen Versionssprung. |
| SHA-256 | `489817ef6cc7b92b4182eeb783c454590f40202a6a134b6a797d9b73549e0f1b` |
| Signierer | `CN=kvmgithub, OU=Android releases`, SHA-256 `73cf309a74b1a107a1e1cbb9453806f7b0d8e803957ecd371cb51cfed0b21e9a`. Genau einer; entspricht `FORK_SIGNER_SHA256`. |
| Paket | `com.project.lol.fork.ui`, Label `MySpotilol`, nicht debuggable, nur `arm64-v8a`. Die Prüfung entfernter Komponenten meldet „none“ (`UI_STRICT_REMOVALS=1`). |
| Testberichte | `test-reports-82efcaa3eb1de21be85fc7dd8b6016a8eff260cd`, ID 14005494258: JUnit-XML und HTML aller Module sowie die TAP-Ausgabe der Node-Tests. |

Herkunft der Werte: Die Prüfsumme und die Signiererzeilen stammen aus dem Job-Log des Laufs. Artefakt-Downloads (Blob-Speicher) sind aus dieser Sitzung gesperrt; die Artefakte selbst konnte ich deshalb nicht öffnen. Das Signiermaterial kam ausschließlich aus dem bestehenden Secret.

## 5. Aktivierung und Logformat

Aktivierung: In den Einstellungen den Schalter „Diagnoseprotokoll“ einschalten. Das ist der vorhandene Schalter, ab jetzt auch für den Sammler. Lesen: „Protokoll anzeigen und kopieren“ (letzte 10 Minuten, geschwärzt) oder der Export aus `Logbook`. Die Zeilen beginnen mit `gain-diag`. Ausgeschaltet entstehen keine Zeilen und keine Analyse; Listener und Timer werden entfernt.

Synthetisches Beispiel. Werte erfunden, Format wie im Code. Die Zeitspalte stammt von `Logbook`.

```
03:41:06.406 gain-diag run=1 seq=0 ms=0 ev=start reason=on v=1 max=256 perS=2 norm=true boost=1.50 xf=12 appAdMute=false table=0 evicted=0 late=0 bad=0 merged=0
03:41:07.218 gain-diag run=1 seq=1 ms=812 ev=answer src=track-playback/state answerCurrent=t1 ctx=playlist tracks=3 ads=0 entries=t1.f1@10/mp4:candidate=-5.1271,t1.f2@12/mp4_dual:candidate=-5.1271,t2.f3@10/mp4:candidate=-5.1465,t2.f4@12/mp4_dual:candidate=-5.1465,t3.f5@10/mp4:candidate=null,t3.?@12/mp4_dual:unknown=absent more=0 table=6 evicted=0 late=0 bad=0 merged=0
03:41:07.936 gain-diag run=1 seq=2 ms=1530 ev=track track=t1 kind=track ctx=playlist el=1 device=local pv=0.8000 pvSt=ok cubic=true vol=0.5120 muted=false paused=false level=0.5120 routed=true pageAdMute=false wrote=restore wroteMs=640 wroteMatch=true norm=true boost=1.50 xf=12 appAdMute=false loud=-7.2140 loudSt=cached gain=f1@10/mp4:candidate=-5.1271,f2@12/mp4_dual:candidate=-5.1271 pageMerged=0 table=6 evicted=0 late=0 bad=0 merged=0
03:41:08.940 gain-diag run=1 seq=6 ms=2534 ev=volume track=t1 kind=track ctx=playlist el=1 device=local pv=0.8000 pvSt=ok cubic=true vol=0.5120 muted=false paused=false level=0.3014 routed=true pageAdMute=false wrote=level wroteMs=3 wroteMatch=true norm=true boost=1.50 xf=12 appAdMute=false loud=-7.2140 loudSt=cached gain=f1@10/mp4:candidate=-5.1271,f2@12/mp4_dual:candidate=-5.1271 pageMerged=4 table=6 evicted=0 late=0 bad=0 merged=2
```

Felder:

- `run`: Messlauf. Neu beim Einschalten und bei `newSession`.
- `seq`: Ereignisfolge im Lauf, monoton. Lücken sind zusammengefasste Ereignisse.
- `ms`: Zeit seit Laufbeginn.
- `t#`/`f#`: lokale Mess-IDs je Lauf.
- `answerCurrent`: der Eintrag, auf den diese Antwort zeigt. Das ist nicht der gespielte Titel.
- `pv`/`pvSt`: rohes `_playerVolume` und sein Status (`ok`, `absent`, `invalid`).
- `wrote`/`wroteMs`/`wroteMatch`: letzte eigene Schreibung der Bridge am selben Element, wie lange sie zurückliegt und ob `el.volume` ihr noch gleicht. Das ist eine Beobachtung, keine Ursachenzuschreibung.
- `routed`: Mitglied von `boost.routed`. Kein Beleg für einen erfolgreichen Anschluss, siehe Risiken.
- `loudSt`: `cached`, `none` (Spotify hat keinen Wert), `not-read` oder `unknown`.
- `table`/`evicted`/`late`/`bad`: Zuordnungen, verdrängte Einträge, verworfene späte Antworten und ungültige Meldungen.

## 6. Abnahme

| Anforderung | Nachweis |
| --- | --- |
| Diagnose aus | Kotlin: `off by default it reads nothing, writes nothing and sets no timer`. JS: `off by default: no message, no listener on the element, no timer`. Spieler-Test: 0 `diag.gain` bei aus. Relay: Gate `gainRun != null` (`host-android`, nicht unit-getestet). Gerätetest offen. |
| Datenqualität | `gain null, absent, invalid and zero stay apart` (null, fehlend, String, 0.0, 1e400, Objekt), `a file without an id is unknown, not a candidate`, `the page's raw player volume, missing or invalid, is said as such`, `loudness is read from what the player has, never asked for`. JS: `a missing or odd _playerVolume is said, not taken as 1`. |
| Zuordnung | `two formats of one song keep their own gain, as offered candidates, not as the file played`, `several songs of one answer each keep their own id, the answer's current one is only the answer's`, `a late answer after a skip never becomes the current song's gain`, `an answer of the run before a logout is dropped and counted`, `another device playing is said, the local element is not taken for what is heard`, `ads are counted, not kept`. |
| Kein Wiedergabeeingriff | `with the diagnostics on the player sends the same playback commands as with them off`. JS: `on or off, the page gets the same state messages and the same levels`; `a volume change is told, with the level the bridge itself wrote` (setzt nur, was verlangt war). Bridge: `a diag message goes to its reader, never into the log nor the player's events`. Relay: liest `sent` erst nach `answer(...)` und gibt nichts zurück. |
| Lebenszyklus | JS: `turned on twice (a resume): no second listener, no second message`, `a change of song is told with its own uri; a change of element moves the listener`, `turned off with a message waiting: nothing more, the listener is gone`. Kotlin: `turning off with a line waiting drops it, a new run never shows it`, `an answer of the run before a logout is dropped and counted`. Neusenden nach Reload: Spieler-Test (genau ein `diag.gain` nach `ready`). Reload und Resume am Gerät offen. |
| Fehler und Last | `257 assignments keep the newest 256`, `a flood of events gives at most two lines a second, the rest merged and counted`, `answers and page events wait apart, a flood of one never hides the other`, `malformed page messages are counted and never written, later ones still are`, `an unreadable answer or a failing source never reaches the caller`. Bridge: `a diag reader that fails ends nothing and logs none of the message`. JS: `a flood: two messages a second, the rest as one with how many it stands for`. |
| Datenschutz | `no token, cookie, URL or Spotify id reaches a line`: markierte Tokens, Cookies, `file_url`, Player-Cookie, Session-ID, Track-UIDs, Device-ID im Pfad, Benutzer-ID im Kontext, Datei-IDs und Fremdfelder in der Seitenmeldung. Bridge-Test: `SECRET` erscheint in keiner Logzeile. |
| APK | Lauf 43355426911 an `82efcaa`; Signierer, Paketname, nicht debuggable und entfernte Komponenten geprüft (siehe 4.). |

Pflichtfälle aus Abschnitt 6:

| Fall | Test |
| --- | --- |
| Zwei Dateiformate | `two formats …` |
| Mehrere Titel einer Antwort | `several songs …` |
| Gain null/fehlend/ungültig/0 | `gain null, absent, invalid and zero …` |
| Nicht geladene Audio-Features | `loudness is read …` (`not-read`) |
| Spät nach Skip | `a late answer after a skip …` |
| Spät nach Logout | `an answer of the run before a logout …` |
| Connect-Gerät | `another device playing …` |
| Fehlendes `_playerVolume` | Kotlin und JS je ein Test |
| Ein-/Ausschalten bei ausstehender Ausgabe | Kotlin `turning off with a line waiting …`, JS `turned off with a message waiting …` |
| 257 Zuordnungen | `257 assignments …` |
| Ereignisflut | Kotlin und JS je ein Test |
| Fehlerhafte Diagnose-Nachricht | Kotlin `malformed page messages …`, Bridge `a diag reader that fails …` |

**Gerätetest durch HK, offen:**

1. Diagnose aus: Titel wechseln; im Protokoll keine `gain-diag`-Zeile.
2. Diagnose an: `ev=start`, danach `ev=answer`- und `ev=track`-Zeilen bei Titelwechseln.
3. Pause und Fortsetzen.
4. App in den Hintergrund, Bildschirm sperren, zurückkehren.
5. Diagnose aus, Protokoll exportieren.
6. Klang unverändert: Crossfade 12 s, Verstärkung, Normalisierung.

## 7. Beobachtungslücken und Risiken

- **Abdeckung des Relays.** Gelesen wird nur, was über `fetch(String|URL)` an `connect-state`/`track-playback` läuft. `fetch(Request)` und Dealer-Nachrichten (`replace_state` über WebSocket; laut Orpheon-Fixtures ebenfalls mit `gain_db`) erreicht der Sammler nicht. Ein fehlender Wert heißt also nicht, dass Spotify ihn nicht geliefert hat.
- **Pegeländerungen ohne `volumechange`.** Ist der Audiographen aktiv (Boost, Crossfade, Normalisierung), laufen Fades über `boost.out.gain`, und `el.volume` bleibt bei `own`. Solche Änderungen lösen kein Ereignis aus. `level` zeigt sie nur, wenn ohnehin eine Zeile entsteht.
- **Zusammengefasste Meldungen lesen den neuesten Zustand.** Bridge und Kotlin liefern beim Senden die aktuellen Werte, nicht die zum Ereigniszeitpunkt. Zwischenzustände einer Flut fehlen; nur ihre Anzahl bleibt.
- **`routed` ist kein Anschlussnachweis.** Das Element wird vor `createMediaElementSource()` in `boost.routed` eingetragen. Unverändert gelassen, wie im Auftrag verlangt.
- **`device`** stammt aus `lastMedia.active`. Der Wert wird alle paar Sekunden gelesen und hinkt einem Gerätewechsel hinterher.
- **`loudness`** liegt nur vor, wenn die Normalisierung sie ohnehin gelesen hat; ohne Normalisierung meist `not-read`.
- **Mess-IDs** gelten je Lauf, Element-IDs je Seite (nach einem Reload wieder `el=1`). Die LRU-Grenze von 256 kann eine ID neu vergeben, wenn ein Titel lange nicht vorkam.
- **Last bei eingeschalteter Diagnose.** Jede 2xx-`track-playback`-Antwort (bis 4 MB) wird auf dem OkHttp-Thread geparst, außerhalb der Sperre. Erfasst und ausgegeben wird unter der Sperre. Seitenmeldungen kommen auf dem Main-Thread an.
- **Der Spieler-Vergleichstest ist zeitbasiert** (`Dispatchers.Default`, 1 s Nachlauf). Seit `82efcaa` ohne zeitabhängige Fades; ein Rest-Flackern ist nicht ausgeschlossen.
- **Der Diagnoseschalter** hat jetzt eine zweite Wirkung. Der Einstellungstext („Blendet darunter den Protokolleintrag ein“) erwähnt sie nicht. Nicht geändert, da es eine UI-Entscheidung ist; der Changelog-Eintrag nennt sie.
- **Keine Aussage über eine Wirkung von `gain_db`.** Die Zeilen ordnen Beobachtungen zu. Ob der Webplayer `gain_db` anwendet, belegen erst ein A/B-Test mit kalibrierter Messstelle (M2) und reale Daten.

Reviewbefunde beantworte ich je Punkt mit „behoben“, „mit Beleg widerlegt“ oder „offen“, jeweils mit neuem Commit und erneutem Lauf.
