# MySpotilol M2.1 – Nacharbeit zum Review: R1, R2 und ein dritter Fehler im selben Pfad

Stand: 14. November 2026. Antwort auf „MySpotilol – Review von M2.1“ (`M2-02-review.md`). Rollen unverändert: Claude implementiert, ChatGPT/Codex prüft, HK entscheidet über Integration und Release.

**Ergebnis:** R1 und R2 sind auf `4656fc3` als rote Tests reproduziert und behoben. Ein zusätzlicher Test für R1 im Play-Zweig der Wiederherstellung deckte einen dritten Fehler im selben Pfad auf (R3). Die Wiederherstellung las den alten Seitenzustand, während der neue noch verarbeitet wurde. Auch R3 ist mit einem eigenen Test reproduziert und behoben. Der Gegenfall mit Positionskorrektur besteht vorher und nachher. Kein Merge, kein Release, `myspoti-ui` unverändert `63bff95`. M2.2 und `SongPlayer` sind nicht berührt.

## 1. Stände

| | |
| --- | --- |
| Arbeitszweig | `work/player-recovery-tests` |
| Ausgangspunkt (rot) | `4656fc3` (M2.1-Korrektur), Spitze vorher `9de96de` (nur Bericht) |
| Review-Ablage | `d7517bd` (`M2-02-review.md`, unveränderte Kopie) |
| Tests vor der Korrektur | `d5e6bb1` (R1, R1b, R2, Gegenprobe), `a9dd3f9` (Fehlermeldung mit Log bei ausbleibendem Aufruf), `a415dd1` (Wartezeit nach Seitenverlust, 5.3), `9bf3e49` (R3) |
| **Korrektur (Ergebnis)** | **`d4c598dff5f6ba62f8655f2841f756d78cfd173d`** |
| Dokumente danach | der Commit mit dieser Datei |
| Diff der Nacharbeit | https://github.com/kvmgithub/Spotilol/compare/4656fc38f67512a6439f50cebf795a65e727b339...d4c598dff5f6ba62f8655f2841f756d78cfd173d |
| Gesamtdiff M2.1 | https://github.com/kvmgithub/Spotilol/compare/4504cde03978154e9dccede180440ff910b8a915...d4c598dff5f6ba62f8655f2841f756d78cfd173d |

Testdateien und Produktionscode sind getrennt committet: Zwischen `9bf3e49` und `d4c598d` ändert sich nur `SpotifyPlayer.kt`.

## 2. Ergebnisse vor und nach der Korrektur

Fundstelle: `base/api-spotify/src/test/kotlin/app/myspoti/spotify/PlayerRecoverySequenceTest.kt`, Zeile des Testbeginns auf `d4c598d`. Produktionscode in allen Vorher-Läufen: `4656fc3`.

| Test | Zeile | Lauf 1 `d5e6bb1` | Lauf 2 `a9dd3f9` | Lauf 3 `a415dd1` | Lauf 4 `9bf3e49` | nach `d4c598d` |
| --- | --- | --- | --- | --- | --- | --- |
| R1 `lateRestoreAnswerKeepsANewerPick_resume` | 326 | rot | rot | rot | rot | grün |
| R1b `lateRestoreAnswerKeepsANewerPick_play` | 343 | rot (Zeitüberschreitung, ohne Log) | rot (R1) | rot (Zeitüberschreitung, R3 laut Log) | rot (R1) | grün |
| R2 `droppedRestoreResumeSendsNoSeek` | 363 | rot | rot | rot | rot | grün |
| R3 `restoreJudgesTheNewPagesState` | 383 | – | – | – | rot | grün |
| Gegenprobe `restoreSetsAFarPositionOnce` | 393 | grün | grün | grün | grün | grün |
| bisherige 9 Playerfälle P1–P5 | 162–301 | grün | grün | grün | grün | grün |
| bisherige 4 Bridgefälle (`BridgeMessagesTest`) | 166–218 | grün | grün | grün | grün | grün |

CI-Läufe: [43586840874](https://github.com/kvmgithub/Spotilol/actions/runs/43586840874), [43587555137](https://github.com/kvmgithub/Spotilol/actions/runs/43587555137), [43588260261](https://github.com/kvmgithub/Spotilol/actions/runs/43588260261), [43588947196](https://github.com/kvmgithub/Spotilol/actions/runs/43588947196). Nach der Korrektur: [43589542281](https://github.com/kvmgithub/Spotilol/actions/runs/43589542281).

`:api-spotify:test` meldete 272 Fälle (Läufe 1–3) bzw. 273 Fälle (Lauf 4). In Lauf 2 scheiterte zusätzlich der unveränderte `PlaybackFollowUpTest` („timed out: repeat set again“), siehe 6.

## 3. Befunde

### R1 – Späte Antwort auf den eigenen Befehl überschreibt eine neuere Auswahl

**Reproduzierter Codefehler, behoben.** Nach dem ersten Befehl übernahm `resumeAfterReload()` die Vergleichskennung aus dem dann aktuellen `lastPlay`. Kam die Benutzerauswahl, während der Befehl unterwegs war, galt sie danach als eigene Kennung. Betroffen sind beide Zweige. R1 testet den Resume-Zweig mit Positionen unter 5 s Abstand, also ohne Seek. R1b testet den Play-Zweig (die neue Seite hat einen anderen Titel wiederhergestellt).

### R2 – Verworfener Resume löst einen Seek aus

**Reproduzierter Codefehler, behoben.** Der in M2.1 eingeführte Abbruch lieferte `JsonNull` wie eine beantwortete Fortsetzung. Bei mehr als 5 s Positionsabweichung folgte deshalb `player.seek`. Der Test hält A bei 98 s; die neue Seite meldet A pausiert bei 0 s.

### R3 – Wiederherstellung liest den alten Seitenzustand

**Reproduzierter Codefehler, behoben.** `onState()` zählte `stateSeq` zu Beginn hoch und setzte `_state` erst am Ende, nach der Titelwechsel-Auswertung. Die Wiederherstellung wartet auf einen höheren Zähler und liest dann `_state`. Im Spalt dazwischen sah sie noch den Zustand der verlorenen Seite („A spielt“) und endete ohne Befehl und ohne Logzeile: `if (s != null && s.item?.uri == uri && !s.isPaused) return`.

Gefunden durch R1b. In zwei von vier Vorher-Läufen kam der Play-Befehl der Wiederherstellung nicht innerhalb von 8 s. Lauf 1 hatte noch keine Fehlermeldung mit Log; Lauf 3 zeigt mit der neuen Meldung die Reihenfolge `page state: "B" paused=true`, dann `page back after a reload: A goes on at 0 s`, dann `track change: A -> B`, danach nichts mehr. R3 macht den Spalt reproduzierbar: Die Testprotokollfunktion schreibt die Zeile „track change“ 500 ms langsam. Diese Zeile entsteht zwischen Zählen und Anzeigen; die Wiederherstellung prüft alle 100 ms. Kein Eingriff in Produktionsuhr oder Produktionscode.

Gerätebezug: Der Spalt besteht auch am Gerät, wenn Android oder die WebView die Seite entfernt und ihr letzter Zustand „spielt“ war. Ob er dort auftrat, belegt kein Gerätelog. Folge wäre eine Wiederherstellung, die ohne Meldung aufgibt.

### Weiter offen

Der Gerätehänger vom 13. November bleibt eine offene Geräteursache. Die Einordnung aus `M2-01-result.md`, Abschnitt 4, gilt unverändert. Die Einschränkungen aus dem Review gelten weiter: P4 injiziert kein `PageDevice.commandLost()`; Android-WebView und `SongPlayer` sind in den JVM-Tests nicht beteiligt.

## 4. Befehlsfolgen

Synthetische Titel: A (verloren), B (von der neuen Seite wiederhergestellt), P (Benutzerauswahl), Liste C. Vorher-Folgen sind wörtlich aus den Fehlermeldungen der Läufe 3 und 4 übernommen. Die Nachher-Folgen sind das, was die grünen Tests prüfen: keine weiteren Wiedergabebefehle nach der Markierung und die genannte Logzeile.

**R1**

```text
vorher:  player.resume {}
         player.play {"contextUri":"spotify:playlist:C","trackUri":"spotify:track:P"}
         player.play {"contextUri":"spotify:playlist:C","trackUri":"spotify:track:A","positionMs":502}
nachher: player.resume {}
         player.play {"contextUri":"spotify:playlist:C","trackUri":"spotify:track:P"}
         Log „page back after a reload: played or picked meanwhile, A not played on“
```

**R1b**

```text
vorher:  player.play {"contextUri":"spotify:playlist:C","trackUri":"spotify:track:A","positionMs":504}
         player.play {"contextUri":"spotify:playlist:C","trackUri":"spotify:track:P"}
         player.play {"contextUri":"spotify:playlist:C","trackUri":"spotify:track:A","positionMs":504}
nachher: die ersten beiden, Log „… played or picked meanwhile, A not played on“
```

**R2**

```text
vorher:  player.resume {}  →  (Pause, nicht gesendet: nichts spielt)  →  player.seek {"positionMs":98502}
nachher: player.resume {}  →  (Pause)  →  Log „player.resume not sent again: paused meanwhile“,
         Log „page back after a reload: paused meanwhile, A not played on“
```

**R3**

```text
vorher:  kein Befehl (die Wiederherstellung hält „A spielt“ der alten Seite für erreicht)
nachher: player.play {"contextUri":"spotify:playlist:C","trackUri":"spotify:track:A","positionMs":…}
```

**Gegenprobe:** vorher und nachher genau `player.resume {}`, dann `player.seek` auf mindestens 98.000 ms, danach nichts.

## 5. Änderungen

### 5.1 Produktion (`SpotifyPlayer.kt`, Zeilen auf `d4c598d`)

| Stelle | Änderung | Befund |
| --- | --- | --- |
| `command()` (2035) | Rückgabe `Boolean` statt der nie genutzten Seitenantwort: `true` angenommen, `false` beim verworfenen Resume. Weiterhin kein Fehler an den Aufrufer. | R2 |
| `resumePage()` (2154) | Gibt `command()`s Ergebnis weiter. Ein Reload an seiner Stelle zählt wie bisher als angenommen. Die übrigen Aufrufer ignorieren den Wert, ihr Verhalten ist unverändert. | R2 |
| `resumeAfterReload()` (680) | Lokale Prüfung `superseded()` (697): Pause (`pausedByUser` oder geänderter `pausesAsked`) oder neueres `lastPlay` beendet die Wiederherstellung, mit Logzeile. Geprüft zu Beginn jedes Versuchs und direkt nach der Antwort auf das eigene Resume, vor dem Seek (717). Verworfenes Resume: Abbruch. Die Vergleichskennung `since` setzt nur noch das eigene Play, und zwar vor dem Senden auf dessen Kennung (726). Die Übernahme aus `lastPlay` nach der Antwort entfällt. | R1, R1b, R2 |
| `playNow()` (1986) | Optionaler Parameter `playedAt` (Vorgabe wie bisher: jetzt), damit der Aufrufer die Kennung seines Plays kennt, bevor es hinausgeht. | R1b |
| `onState()` (1580) | Dünne Hülle: `try { takeState(s) } finally { stateSeq++ }`. Der bisherige Rumpf heißt `takeState()` und ist bis auf das entfernte `stateSeq++` unverändert. Der Zähler steigt erst, wenn der Zustand übernommen und angezeigt ist. Alle Stellen, die `stateSeq` lesen, warten damit auf einen neuen Zustand der Seite (`awaitPageSettled`, die Skip-Absicht und die Zustandswartezeit in `resumeAfterReload`); keine braucht den früheren Zeitpunkt. | R3 |

Verhaltensänderungen über die Befunde hinaus:

- Die Wiederherstellung endet jetzt auch bei einer Pause mit langer Ausblendung (Schlaftimer, `pauseSlowly`), die `pausedByUser` erst am Ende setzt. Vorher hätte sie währenddessen weiterversucht. Kein Test belegt diesen Fall.
- Jedes Ende der Wiederherstellung durch Pause oder Auswahl schreibt eine Logzeile. Vorher war das nur beim ersten Versuch und nur bei einer Auswahl so.

Unverändert: öffentliche Player-Schnittstelle, `COMMAND_RETRIES`, `COMMAND_RETRY_MS`, `READY_WAIT_MS`, `TRANSIENT`, `JOIN_WAIT_MS`, `RESTORE_WAIT_MS`, `RESTORE_CHECK_MS`, `RESTORE_GAP_MS`, `RESTORE_ATTEMPTS`, die 5-s-Schwelle für den Seek, Audio-, Netz-, Anmelde- und DRM-Regeln, `Bridge.kt`, `PageDevice.kt`, `SongPlayer.kt`.

### 5.2 Tests

- Fünf neue Fälle (R1, R1b, R2, R3, Gegenprobe). Die bisherigen 13 bleiben erhalten.
- Reihenfolge über Ereignisse, wie im Review verlangt: erst den konkreten ausstehenden Aufruf abwarten (`next(...)`), dann Auswahl oder Pause auslösen, dann die alte Antwort freigeben. Geprüft werden alle Play-, Resume-, Seek-, Next-, Previous- und SkipTo-Befehle ab einer Markierung, die nach der Benutzeraktion gesetzt wird. Bereits abgesandte Befehle liegen vor der Markierung.
- Testprotokolle mit Positionsparameter (`state`, `showing`, `restored`, `pageLostWhilePlaying`; Vorgabe 0).
- `next(method, ms)`: wie `awaitCall`, scheitert aber mit gesendeten Befehlen und den letzten Logzeilen. Damit wurde R3 sichtbar.
- Nachweis, dass die Wiederherstellung lief: P5a, P5b, R1, R1b und R2 verlangen zusätzlich die Logzeile „… meanwhile, A not played on“. Ohne sie bestünde ein Test, der „kein Befehl nach der Benutzeraktion“ prüft, auch dann, wenn gar keine Wiederherstellung lief. Alle übrigen Wiederherstellungsfälle warten auf deren Befehl oder deren Log.

### 5.3 Zeitbasierte Stelle im Testaufbau

`pageLostWhilePlaying()` wartet nach dem Seitenverlust 300 ms, bevor die neue Seite „ready“ meldet. Grund: `Bridge.ready` ist ein `StateFlow`. Folgen „nicht bereit“ und „bereit“ unmittelbar aufeinander, sieht ein verspäteter Sammler des Players keine Änderung, und keine Wiederherstellung läuft. Ein beobachtbares Signal dafür gibt es im Player nicht, und ich habe keinen Testzugang in die Produktion eingebaut. Ein verpasster Verlust lässt die Tests deshalb scheitern, nicht fälschlich bestehen (5.2). Ich hielt diesen Wettlauf zuerst für die Ursache der Zeitüberschreitung in Lauf 1. In Lauf 3 lief die Wiederherstellung aber, die Ursache dort war R3; für Lauf 1 ohne Log ist keine der beiden Ursachen belegt. Die Wartezeit bleibt als Absicherung, nicht als Korrektur eines beobachteten Fehlers. In der App liegen zwischen Seitenverlust und neuer Bereitschaft das Laden der Seite und die Injektion der Bridge. Dort erwarte ich den Wettlauf nicht, geprüft habe ich das nicht.

## 6. Prüfungen

| Prüfung | Ergebnis |
| --- | --- |
| CI [43589542281](https://github.com/kvmgithub/Spotilol/actions/runs/43589542281) auf `d4c598d` | grün, `BUILD SUCCESSFUL in 6m 10s`, Node 23/23, `:api-spotify:test`, `:api:test`, `:app:testDebugUnitTest`, `:host-android:testDebugUnitTest` gelaufen; „Verify and name APK“ bestanden, „Publish release“ übersprungen |
| zweiter CI-Lauf auf `d4c598d` (Stabilität) | [43590152430](https://github.com/kvmgithub/Spotilol/actions/runs/43590152430): grün, `BUILD SUCCESSFUL in 6m 23s`, Node 23/23 (Abschnitt 8) |
| `node --test base/api-spotify/bridge-tests/gain-diagnostics.test.mjs` | lokal 23/23 |
| `python3 .github/scripts/test_ui_release_notes.py` | lokal bestanden |
| gezielter lokaler Gradle-Lauf | weiterhin nicht möglich (Maven Central vom Proxy gesperrt) |

Die JUnit-Fallzahlen des grünen Laufs stehen nur in den Testberichten (Artefakt 14111449644); herunterladen kann ich sie hier nicht. Erwartet sind 273 in `:api-spotify`, wie in Lauf 4.

**Bekannte Testinstabilität außerhalb dieser Änderung:** `PlaybackFollowUpTest > the repeat mode the user chose is set again when a play leaves the page on another()` scheiterte in Lauf 2 mit „timed out: repeat set again“. In den anderen vier Läufen dieser Nacharbeit bestand er; sein Testcode ist unverändert. Das Muster passt zum bekannten Abonnement-Wettlauf älterer Playertests (`M2-01-result.md`, 6.3); bewiesen ist die Ursache nicht. Ich habe ihn nicht angefasst.

### APK

| | |
| --- | --- |
| Lauf | 43589542281, Artefakt [apk-d4c598d…](https://github.com/kvmgithub/Spotilol/actions/runs/43589542281/artifacts/14111594414) |
| Datei | `MySpotilol-arm64-1.4.0.apk` |
| SHA-256 | `2e368168d3eb1624d65ee18de5f056d4d0e77652105cf8fc8351406015dbdcfc` |
| Signierer | `73cf309a74b1a107a1e1cbb9453806f7b0d8e803957ecd371cb51cfed0b21e9a` (V2, aus dem CI-Log) |
| Prüfung | CI-Schritt „Verify and name APK“ bestanden: genau ein Signierer mit erwarteter Prüfsumme, Paket `com.project.lol.fork.ui`, Version, App-Name, nicht debugfähig, keine Installer-Berechtigung, nur `arm64-v8a`, ausgeschlossene Klassen nicht enthalten |
| Version | weiterhin 1.4.0 / 1004000; Unterscheidung nur über Commit und SHA-256 |

## 7. Nicht Teil dieser Nacharbeit

- M2.2 und `SongPlayer` (Hypothese und Testzugang wie in `M2-01-result.md`, Abschnitt 8).
- Die Asymmetrien aus `M2-01-result.md`: Wiederholung einer Pause nach neuerem Resume und Wiederholung von Play, Next oder SkipTo nach einer Pause, weiter ungeprüft.
- Exportlücke `displayName`/`accountId`, Gain-Anwendung, native Wiedergabe.
- Gerätetest: Aus R1–R3 folgt kein gezielt auslösbarer Gerätefall. R3 träfe am ehesten einen von Android entfernten Player, dessen neue Seite einen anderen Titel wiederherstellt.

## 8. Stabilitätslauf

Lauf 43590152430 auf demselben Commit `d4c598d`: grün, alle Testaufgaben und die APK-Prüfung bestanden. Zwei grüne Läufe nacheinander belegen keine Freiheit von Wettläufen, schließen aber eine häufige Instabilität der neuen Fälle aus.

Die APK dieses Laufs hat eine andere Prüfsumme (`1d4c950d79407ba17b5ec81801dad848b6cf1aeb5272cfd6e56ae033e4aa29ba`). Der Build ist also nicht bitgleich reproduzierbar. Maßgeblich für eine Gerätefreigabe ist die APK aus Lauf 43589542281 mit der Prüfsumme in Abschnitt 6.
