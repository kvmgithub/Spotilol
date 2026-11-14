# MySpotilol M2.1 – Ergebnis: steuerbare Bridge-Antworten und Regressionstests

Stand: 14. November 2026. Antwort auf „MySpotilol – M2-Auswertung und nächster Auftrag für Claude“ (`M2-00-auftrag.md`). Rollen unverändert: Claude implementiert, ChatGPT/Codex prüft, HK entscheidet über Integration und Release. Die im Auftrag genannte Grundlage `MySpotilol-M1-Abschluss-Reload-2026-11-14.md` liegt mir nicht vor; der Gerätebezug stützt sich auf die Ereignistabelle des Auftrags (Abschnitt 2) und `M1-13`.

**Ergebnis:** Zwei Fehler im Player sind reproduziert und mit kleinen Korrekturen behoben: ein erneut gesendetes Fortsetzen nach einer Benutzerpause (P2 in beiden Reihenfolgen und P3b) und eine Wiederherstellung, die eine während der Gerätewartezeit getroffene Titelwahl überspielt (P5a). Alle anderen Szenarien bestanden bereits vorher. Die Ursache des Geräte-Hängers vom 13.11. um 21:03 ist damit **nicht** gefunden (Abschnitt 4). Kein Merge, kein Release.

## 1. Stände

| | |
| --- | --- |
| Arbeitszweig | `work/player-recovery-tests`, neu angelegt von der Spitze von `work/gain-diagnostics` (`4504cde`, M1-Code `18c323f` plus Dokumente) |
| Produktbranch | `myspoti-ui` unverändert `63bff95` |
| Testvorbereitung | `8c45cd3` (Testhost, B1–B3, P1–P5, `PageDevice.resetForTests`), `d644efe` (Zustand im Test wiederholt senden, siehe 6.3) |
| **Korrektur (Ergebnis)** | **`4656fc38f67512a6439f50cebf795a65e727b339`** |
| Dokumente danach | der Commit mit dieser Datei |
| Diff M2.1 | https://github.com/kvmgithub/Spotilol/compare/4504cde...4656fc38f67512a6439f50cebf795a65e727b339 |
| CI vor der Korrektur | [43548649414](https://github.com/kvmgithub/Spotilol/actions/runs/43548649414) auf `8c45cd3`: 268 Tests in `:api-spotify:test`, 5 rot (die vier erwarteten Szenarien und ein bestehender Test, 6.3) |
| CI nach der Korrektur | [43549470285](https://github.com/kvmgithub/Spotilol/actions/runs/43549470285) auf `4656fc3`: grün, `BUILD SUCCESSFUL in 5m 33s`, mit `:api-spotify:test` und `:host-android:testDebugUnitTest` |

## 2. Ergebnis je Test

Fundstellen: `base/api-spotify/src/test/kotlin/app/myspoti/spotify/bridge/BridgeMessagesTest.kt` (B) und `…/spotify/PlayerRecoverySequenceTest.kt` (P), Zeile des Testbeginns.

| Test | Fundstelle | Vor der Korrektur | Nach der Korrektur | Einordnung |
| --- | --- | --- | --- | --- |
| Hilfstest: zwei gleichnamige Aufrufe, umgekehrt beantwortet | B:166 | grün | grün | Testhost-Nachweis |
| B1 `pendingCallFailsWhenPageGoesAway` | B:181 | grün | grün | bestandenes Zusatzszenario |
| B2 `lateOldResultCannotCompleteNewCall` | B:200 | grün | grün | bestandenes Zusatzszenario |
| B3 `cancelledCallDoesNotBreakNextCall` | B:218 | grün | grün | bestandenes Zusatzszenario |
| P1 `transientResumeFailureRetriesAndRecovers` | P:147 | grün | grün | Referenz |
| P2a `…_pauseBeforeTheFailure` | P:173 | **rot** | grün | reproduzierter Codefehler F1, behoben |
| P2b `…_pauseDuringTheBackoff` | P:186 | **rot** | grün | reproduzierter Codefehler F1, behoben |
| P3a `…_pauseBeforeTheAnswers` | P:200 | grün | grün | bestandenes Zusatzszenario |
| P3b `…_pauseBeforeTheNextAttempt` | P:214 | **rot** | grün | reproduzierter Codefehler F1 (Restore-Pfad), behoben |
| P3, Gegenfall ohne Benutzeraktion | P:229 | grün | grün | positiver Gegenfall |
| P4 `readyWithoutJoinedDeviceIsNotPlaybackConfirmation` | P:242 | grün | grün | bestandenes Zusatzszenario |
| P5a `…_pickWhileTheDeviceIsAwaited` | P:274 | **rot** | grün | reproduzierter Codefehler F2, behoben |
| P5b `…_pickWhileTheRestoredStateIsAwaited` | P:285 | grün | grün | bestandenes Zusatzszenario |

Was die grünen Fälle zeigen:

- **B1–B3:** Ein ausstehender Aufruf scheitert beim Seitenwechsel sofort mit „page reloaded“. Eine späte Antwort der alten Seite schließt den neuen Aufruf nicht, das belegt eine Ordnungsprobe über einen weiteren Aufruf. Ein abgebrochener Aufruf bleibt abgebrochen, seine späte Antwort ändert nichts, und die Bridge sendet nichts neu.
- **P1:** Ein Fehler, danach genau eine Wiederholung. Nach dem Erfolg kommt kein weiterer Aufruf, und der Status bleibt pausiert, bis die Seite Wiedergabe meldet.
- **P3a:** Nach einer Pause während der Gerätewartezeit prüft die Wiederherstellung den Titel noch, sendet aber weder Play noch Resume.
- **P4:** Die Wartezeit auf das Gerät (5 s) und Prüfung plus Abstand der Wiederherstellung (5 s + 1,5 s) werden eingehalten. Eine von der Seite angenommene Fortsetzung zählt nicht als Wiedergabe. Nach der späten Geräteanmeldung folgt genau ein weiterer Versuch, danach keiner mehr.
- **P5b:** Eine Titelwahl nach Beginn der Zustandswartezeit wurde schon vorher erkannt.

## 3. Befundstatus

### Reproduzierte Codefehler

**F1 – Fortsetzen nach Benutzerpause erneut gesendet.** `SpotifyPlayer.command()` wiederholt einen Befehl nach einem vorübergehenden Fehler (`No such device`, `not ready`, `page reloaded`, `local player is disabled`). Vor dem erneuten Senden hat es nicht geprüft, ob der Benutzer inzwischen pausiert hat. Betroffen waren direktes Fortsetzen (P2) und das Fortsetzen der Wiederherstellung nach einem Reload (P3b). Die Pause selbst wird bei pausiertem Seitenzustand gar nicht gesendet („pause: nothing plays, not sent“). Ohne Korrektur startete die Musik also gegen die letzte Benutzeraktion.

**F2 – Wiederherstellung überspielt neuere Titelwahl.** Nach dem Verlust einer spielenden Seite merkte sich `resumeAfterReload` den letzten Abspielbefehl erst nach `awaitDeviceJoined()`. Eine Titelwahl in dieser Wartezeit galt daher als alt, und die Wiederherstellung spielte den verlorenen Titel über sie.

### Bestandene Zusatzszenarien

B1–B3, P1, P3a, der P3-Gegenfall, P4 und P5b (Abschnitt 2).

### Offene Geräteursache

Abschnitt 4.

## 4. Abgleich mit dem Gerätelog vom 13.11. (21:03–21:04 MEZ)

| Gerät | Bezug zu F1/F2 |
| --- | --- |
| 21:03:11.621 `player.resume FAILED: No such device.`, „sent again“ | Wiederholungsweg von `command()`. Eine Pause lag nicht dazwischen; F1 greift hier nicht. |
| 21:03:12.321 der wiederholte `player.resume` beginnt; die Seite antwortet nicht, bis er nach 11.885 ms beim nächsten Skip abgebrochen wird | Nicht durch F1/F2 erklärt. Die Bridge selbst verhält sich nach B1–B3 korrekt. Die fehlende Antwort kommt aus der Seite (`player.resume` im Webplayer) oder aus deren Zustand nach dem Reload. |
| 21:03:32–34 Pause zweimal wiederholt, Seite folgt nicht | Liegt in `SongPlayer.ensurePaused()` und in der Seite, außerhalb der hier getesteten Schicht. |
| 21:03:09.587 eigener Reload wegen hängender Werbung | `reloadAndPlayOn(last)` mit `next = true`, also `playPickAfterReload` bzw. `playOnNewPage { playOn(…) }`; die Wiederherstellung im Ready-Sammler läuft bei eigenem Reload nicht, `resumeAfterReload` wird nicht erreicht. F2 greift hier nicht. |

**Status: offene Geräteursache.** Die Korrekturen schließen die zwei nachgewiesenen Fehlerklassen. Den beobachteten Hänger reproduzieren sie nicht.

## 5. Vorher/Nachher-Befehlsfolgen

Synthetische Titel A (verloren bzw. laufend) und P (Auswahl). Folgen aus den Testmeldungen des Laufs 43548649414 (vorher) bzw. aus dem Testablauf nach der Korrektur (nachher). Ohne Hintergrundlesungen.

**P2a/P2b:** A pausiert, Fortsetzen, Seite antwortet „No such device.“, Benutzer pausiert (vor bzw. nach der Fehlerverarbeitung).

```text
vorher:  player.resume {}  →  (Pause, nicht gesendet: nichts spielt)  →  player.resume {}
nachher: player.resume {}  →  (Pause)  →  Log „player.resume not sent again: paused meanwhile“
         danach ausdrücklich: player.resume {}  (beantwortet, funktioniert)
```

**P3b:** Seite weg während A spielt. Neue Seite bereit, Gerät angemeldet, Wiederherstellung sendet Fortsetzen, „No such device.“, Benutzer pausiert.

```text
vorher:  player.resume {}  →  (Pause)  →  player.resume {}
nachher: player.resume {}  →  (Pause)  →  kein weiterer Befehl; der nächste Versuch endet an pausedByUser
```

**P5a:** Seite weg während A spielt. Neue Seite bereit, Gerät noch nicht angemeldet, Benutzer wählt P, Gerät meldet sich an, Seite spielt P.

```text
vorher:  player.play {"contextUri":"spotify:playlist:C","trackUri":"spotify:track:P"}
         player.play {"contextUri":"spotify:playlist:C","trackUri":"spotify:track:A","positionMs":23}
nachher: player.play {"contextUri":"spotify:playlist:C","trackUri":"spotify:track:P"}
         Log „page back after a reload: played or picked meanwhile, A not played on“
```

## 6. Änderungen

### 6.1 Produktionsdateien

| Datei | Änderung | Begründung |
| --- | --- | --- |
| `SpotifyPlayer.kt`, `command()` | `pausesAsked` beim ersten Senden merken; vor dem erneuten Senden eines `player.resume` abbrechen, wenn sich der Wert geändert hat. Ergebnis `JsonNull`, Logzeile, kein Fehler an den Aufrufer: Die Pause gilt als die neuere Absicht. | F1. Nur `player.resume`, weil nur dafür ein Fehler belegt ist. Pause, Play, Next, Seek und alle übrigen Befehle wiederholen wie bisher. |
| `SpotifyPlayer.kt`, `resumeAfterReload()` und seine zwei Aufrufer | Neuer Parameter `playedBefore`. Die beiden Aufrufer (Ready-Sammler, `reloadAndPlayOn` mit `next = false`) übergeben `lastPlay` aus der Zeit vor `awaitDeviceJoined()`. Logzeile beim Abbruch. | F2. Dieselbe vorhandene Kennung (`lastPlay`), nur früher gelesen; keine neue Zustandsmaschine. |
| `bridge/PageDevice.kt` | `internal fun resetForTests()` | Testzugang: Der Zustand ist prozessweit, und `reported` kann sonst nur wahr werden. Nur in Tests aufgerufen, keine Reflexion. Im Testvorbereitungs-Commit. |

Unverändert: `COMMAND_RETRIES` (3), `COMMAND_RETRY_MS` (700), `READY_WAIT_MS` (30 s), die Liste `TRANSIENT`, `JOIN_WAIT_MS` (5 s), `RESTORE_WAIT_MS` (6 s), `RESTORE_CHECK_MS` (5 s), `RESTORE_GAP_MS` (1,5 s), `RESTORE_ATTEMPTS` (3), `UNJOINED_*`, `DEVICE_LOST_WAIT_MS`, Audio-, Netz-, Anmelde- und DRM-Regeln, `Bridge.kt`, `SongPlayer.kt`.

### 6.2 Testdateien

- Neu `ScriptedBridgeHost.kt`: Vertrag wie im Auftrag (`awaitCall`, `succeed`, `fail`, `post`), dazu `answer(method)` für ausdrücklich benannte Hintergrundmethoden je Szenario, `calls` als geordnete Historie und `awaitSubscriber()`. Er liest nur die Aufrufhülle und hat keine eigene Player- oder Retry-Logik. Offene Aufrufe bleiben offen.
- Erweitert `BridgeMessagesTest.kt`: Hilfstest und B1–B3; die bestehenden Fälle sind unverändert.
- Neu `PlayerRecoverySequenceTest.kt`: P1–P5 mit echter `Bridge` und echtem `SpotifyPlayer`, echte Zeit mit den Produktionsfristen, jede Wartebedingung begrenzt. Hintergrundmethoden (`media.state`, `bridge.version`, `volume.get`, Pegelschritte usw.) werden mit `null` beantwortet wie in den bestehenden Playertests. Wiedergabebefehle bleiben offen, bis der Test antwortet. `PageDevice`, `PageAuth` und `AdBlock.ahead` werden vor und nach jedem Test zurückgesetzt.

### 6.3 Nebenbefund: Wettlauf in bestehenden Playertests

Im Lauf 43548649414 scheiterte zusätzlich der unveränderte `PickDuringAdTest` („timed out: ad flag“). Ursache im Testaufbau: `bridge.events` ist ein `SharedFlow` ohne Wiedergabepuffer. Eine Zustandsmeldung, die gesendet wird, bevor der Ereignissammler des Players abonniert hat, geht verloren; die Tests warten nur auf das Abonnement der Bridge. Meine neuen Szenarien hatten dieselbe Lücke und wiederholen die Zustandsmeldung jetzt, bis der Player sie zeigt (`d644efe`). Die bestehenden Tests (`PickDuringAdTest`, `ReloadTest`, `SpotifyPlayerCharacterizationTest` und weitere mit demselben Muster) habe ich nicht geändert. Sie können unter CI-Last gelegentlich scheitern. Eine Testkorrektur in diesem Sinn wäre ein eigener kleiner Auftrag.

## 7. Prüfungen

| Prüfung | Ergebnis |
| --- | --- |
| `node --test base/api-spotify/bridge-tests/gain-diagnostics.test.mjs` | lokal 23/23, in CI 23/23 |
| `python3 .github/scripts/test_ui_release_notes.py` | lokal bestanden |
| CI `./gradlew testDebugUnitTest :api:test :api-spotify:test :app:assembleRelease …` | vorher 5 rot (Abschnitt 1), nachher grün |
| Gezielter lokaler Gradle-Lauf aus dem Auftrag | in dieser Umgebung nicht möglich (Maven/Gradle vom Proxy gesperrt); die Tests laufen vollständig in der CI |

Die JUnit-Fallzahlen des grünen Laufs stehen nur in den [Testberichten](https://github.com/kvmgithub/Spotilol/actions/runs/43549470285/artifacts/14095718783); herunterladen kann ich sie hier nicht. Neu sind 13 Fälle: 4 in `BridgeMessagesTest`, 9 in `PlayerRecoverySequenceTest`.

### APK (Produktionsänderung)

| | |
| --- | --- |
| Artefakt | [apk-4656fc3…](https://github.com/kvmgithub/Spotilol/actions/runs/43549470285/artifacts/14095464072), `MySpotilol-arm64-1.4.0.apk` |
| SHA-256 | `15c7a914572b85e83aa2590b1e4dfa2b69df208619e3bd4c4e1e0fdb8f650637` |
| Signierer | `73cf309a74b1a107a1e1cbb9453806f7b0d8e803957ecd371cb51cfed0b21e9a` |
| APK-Prüfungen | CI-Schritt „Verify and name APK“ (Paket, Signierer, Architektur) bestanden; „Publish release“ übersprungen (Arbeitszweig) |
| Version | weiterhin 1.4.0 / 1004000; Unterscheidung nur über Commit und SHA-256 |

## 8. Verbleibende Abdeckung und Hypothesen für M2.2

**`SongPlayer` ist nicht getestet.** Die Transportwarteschlange liegt oberhalb von `SpotifyPlayer` in `base/app`: `transportGen`, `transportLock`, Abbruch eines laufenden Transports nur bei Eingang eines neueren und nur ab 3.000 ms Laufzeit (`TRANSPORT_STUCK_MS`), Zeitlimit des Befehlsblocks 15.000 ms, `ensurePaused()` mit Wiederholungen nach je 1.200 ms. Zu prüfende Hypothese aus dem Auftrag, Abschnitt 7: Ein laufender Transport antwortet nicht. Pause und beide Wiederholungen kommen, während er beim jeweiligen Eingang jünger als 3 s ist. Wann läuft die neueste Pause tatsächlich, und verändert ein später alter Abschluss die Anzeige?

**Kleinster Testzugang dafür:** `SongPlayer` hängt an Android (`SystemClock`, `Log`, Hauptthread, `api`-Fassade). Ein JVM-Test braucht entweder Robolectric oder das Herauslösen der Transportsteuerung in eine reine Klasse mit eingespeister Uhr und eingespeistem Befehlsblock. Diese Klasse würde `SongPlayer` unverändert verwenden. Ich empfehle Letzteres als eigenen Commit in M2.2, mit Tests gegen die herausgelöste Klasse, nicht gegen eine Nachbildung.

**Bewusst nicht geändert, ungeprüft:**

- Wiederholung einer Pause nach einem neueren Fortsetzen.
- Wiederholung von Play, Next, Previous oder SkipTo nach einer Pause.
- Wiederholungen nach einer neueren Auswahl.

Dasselbe Muster wie F1 ist denkbar, aber durch keinen Test belegt.

**Nicht berührt:** die Exportlücke für `displayName`/`accountId` (separater Härtungsauftrag), Gain-Anwendung, native Wiedergabe.

Ein neuer Gerätedurchgang folgt aus F1/F2 nur, wenn HK das Verhalten am Gerät sehen will: „Pause, während nach einem Reload die Musik zurückkommt“ und „Titelwahl direkt nach einem Reload“. Beide sind kaum gezielt auslösbar; die Tests decken sie ab.
