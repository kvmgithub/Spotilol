# MySpotilol M1 – Übergabe 2 an ChatGPT/Codex (Korrekturen nach Review 1)

Stand: 13. November 2026. Antwort auf „MySpotilol – unabhängiges Review von M1“ (`M1-03-review-1.md`). Rollen unverändert: Claude implementiert, ChatGPT/Codex prüft lesend, HK testet am Gerät und entscheidet.

**Ergebnis:** R1 bis R5 und die nachrangigen Punkte sind behoben, R6 ist bis auf einen Teil behoben (Relay-Vergleich an/aus auf Android-Ebene, siehe 2.6). Node-Tests, Release-Notes-Prüfung und der vollständige CI-Lauf mit allen Unit-Tests sind auf dem Ergebniscommit grün, die APK ist mit dem vorgesehenen Schlüssel signiert. Offen bleiben dieser Relay-Teil und HKs Gerätetest. Nichts wurde gemergt oder veröffentlicht, `myspoti-ui` ist unberührt.

Ab dieser Runde liegen Auftrag, Übergaben und Review im Repository unter `docs/agent-handoff/` (HKs Freigabe vom 13.11.: „Ab jetzt dürfen KI-Artefakte in diesem Repo sein.“). Commits tragen deshalb jetzt die Trailer `Co-Authored-By: Claude Opus 5.5` und `Claude-Session`.

## 1. Stände

| | |
| --- | --- |
| Basis | `64f97bb9ffe517b2f40ded45b7e934e23ec6a11a` (`myspoti-ui`, MySpotilol 1.4.0) |
| Reviewstand | `82efcaa3eb1de21be85fc7dd8b6016a8eff260cd`; der Arbeitszweig stand beim Beginn dieser Runde unverändert dort, es gab keine zwischenzeitliche Arbeit |
| Ergebnis (Code) | `2329f0f9a73b88db142d2fff2f29c333fa1f1bdb` |
| Dokumentcommits | `ddf6f00` (Entwurf) und der Commit mit dieser Fassung. Sie ändern nur `docs/agent-handoff/`; auf diesem Zweig startet die CI nur per Hand, der Code-Stand der APK ist `2329f0f`. |
| Diff gegen Reviewstand | https://github.com/kvmgithub/Spotilol/compare/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd...2329f0f9a73b88db142d2fff2f29c333fa1f1bdb |
| Diff gegen Basis | https://github.com/kvmgithub/Spotilol/compare/64f97bb9ffe517b2f40ded45b7e934e23ec6a11a...2329f0f9a73b88db142d2fff2f29c333fa1f1bdb |
| Produktbranch | `myspoti-ui` steht inzwischen auf `63bff95` („Update README.md“, nur `README.md`). Keine Überschneidung mit den Dateien dieses Zweigs; nicht eingearbeitet, da kein Merge beauftragt ist. |

Commits dieser Runde:

| Commit | Inhalt |
| --- | --- |
| `aa4ab33` | R1–R6: Isolierung und Fehlercodes in der Bridge, Seiten-/Laufbindung, Herkunft je Beobachtung, geordneter Befehlsvergleich, Loudness-Zustände, Schaltertext |
| `2329f0f` | Längengrenze der Seitenzeilen (Logcat), Zahlen außerhalb ±1e6 als `out-of-range`; Testname mit `:` korrigiert (CI-Lauf 43387905777 scheiterte daran beim Kompilieren der Tests) |

Geänderte Dateien gegenüber dem Reviewstand: `player-bridge.js`, `GainDiagnostics.kt`, `SpotifyPlayer.kt`, `SpotifyApi.kt`, `ControlRelay.kt`, `spotilol_strings.xml` (en, de), `CHANGELOG-UI.md`, `GainDiagnosticsTest.kt`, `gain-diagnostics.test.mjs`. Nicht geändert: `Bridge.kt` (der `diag`-Abzweig aus M1 bleibt), Workflow, Paketname, Version, Signierung.

## 2. Befunde

| Befund | Status | Kern der Korrektur | Tests |
| --- | --- | --- | --- |
| R1 Fehlerisolation | behoben | Jeder Diagnoseschritt in der Bridge läuft in `gdTry`; ein Fehler wird zu einem festen Code und bricht nur die Diagnose ab | JS 316, 344, 395; Kotlin 26 |
| R2 Player/Element | behoben | Spielerwechsel, fehlendes Element und später erscheinender Player werden am vorhandenen Statusereignis erkannt; veraltete Suchen binden nicht | JS 230, 250, 268, 284, 170 |
| R3 Lauf/Seite | behoben | Seitentag je Seite und Lauf; Relay-Ticket beim Senden; alte Seite, alter Lauf, alte Bewaffnung werden verworfen und gezählt | Kotlin 11, 12, 13; JS 178, 301, 381 |
| R4 Herkunft | behoben | Bis zu vier Beobachtungen je Kandidat mit Anfragefolge, Rolle, Kontext, Gerät; Seitenzeile zeigt neueste, abweichende und `here` | Kotlin 4, 10, 14, 15, 16, 17, 18; JS 215 |
| R5 Positivliste | behoben | Feste Wörter für Manifestgruppen, unbekannte nur gezählt; `diag.gain` antwortet nie mit geworfenem Text; feste Fehlertexte in Kotlin | Kotlin 28, 26, 25; JS 355, 316, 344 |
| R6 Gleichheitstest | teilweise behoben | Geordnete Folge aus Methode und Argumenten, ein Thread, definierte Abschlüsse, Empfindlichkeitsnachweis | Kotlin 29, 30, 31 |
| R6 Relay an/aus | offen | Strukturell belegt (2.6), kein Test auf Android-Ebene | – |

Die Testnummern verweisen auf die Listen in Abschnitt 4. Die JS-Nummern sind die Zeilen in `gain-diagnostics.test.mjs`, an denen der jeweilige Test beginnt.

### 2.1 R1 – Fehlerisolation

- Alle Diagnosestellen der Bridge laufen über `gdTry(code, fn, fallback)`: `pushState` (`state`), Lesen der Pegelwerte (`read`), Playersuche (`locate`, auch in der asynchronen Fortsetzung), Ausgabe (`flush`), Schreibmarken in `setLevel`/`restoreOwn`/`media.mute` (`write`), Listener-Wechsel (`follow`) und der Befehl selbst (`command`). Ein Fehler erhöht `faults` und setzt `fault` auf genau einen dieser sieben Codes. Freier Fehlertext wird nirgends übernommen.
- Lesefehler einzelner Felder ergeben `pvSt=error` beziehungsweise `unknown`, die Zeile geht trotzdem hinaus.
- Gegenprobe des Reviews als Test (JS 316): `_playerVolume` wirft, Titelwechsel A → B, Diagnose an und aus. Gleiche `state`- und `queue`-Meldungen, Zustand von B gesendet, gleicher Elementpegel; danach wird ein gültiger Wert wieder gelesen (`pvSt=ok`, `pv=0.9`). Kein `RAWMARKER` in irgendeiner Nachricht.
- Nebenbefund, nicht geändert: Der bestehende Pegelcode der Bridge (`ownLevel`) liest `_playerVolume` ebenfalls. Ein werfender Getter trifft deshalb `media.volume` unabhängig von der Diagnose. Das ist Bestandsverhalten aus 1.4.0 und gehört nicht zu M1; der Test prüft daher bewusst nur den Titelwechsel.

### 2.2 R2 – Player und Element

- `gdTrack()` vergleicht bei jedem vorhandenen Statusereignis das aktuelle Player-Promise des SDK mit dem gebundenen. Neues Promise: alter Listener wird entfernt, Player und Element gelten als unbekannt, die Suche bindet neu und meldet `ev=player`. Gleiches Promise, anderes oder kein Element: Wechsel des Listeners und `ev=element` (auch zu „kein Element“, `el=0`).
- Eine Suche bindet nur, wenn Diagnose, Bewaffnungsgeneration und Promise noch dieselben sind (`gdLocate`). Spät aufgelöste Suchen eines abgelösten Players oder einer früheren Bewaffnung werden verworfen.
- Keine Pollingschleife, keine Timer außer der bestehenden Zeilenbegrenzung, keine Requests.
- Tests: neuer SDK-Player mit eigenen Werten und eigenem Element, alter Listener gelöst (JS 230); A → kein Element → B (JS 250); Player erscheint nach dem Einschalten unter gleichem Titel (JS 268); veraltete Suche (JS 284); erneute Bewaffnung mit gleichem Tag wie bei einem Resume ohne zweiten Listener und ohne zweite Meldung (JS 170).

### 2.3 R3 – Messlauf und Ursprungsseite

- Kotlin vergibt je Seite und Lauf einen Tag (`armPage()`), nie wiederverwendet. Die Seite erhält ihn mit `diag.gain {on:true, tag}` und schickt ihn in jeder Meldung mit. Angenommen wird nur der zuletzt vergebene Tag.
- Relay: `relayTicket()` wird beim Senden des Requests gezogen und enthält Tag und Anfragefolge. Antworten mit anderem Tag werden verworfen (`late`).
- `newRun()` (Logout, Profilwechsel, Sitzungsverlust über `SpotifyApi.newSession()`) und `pageGone()` (Seite nicht mehr bereit) lassen keine Seite bewaffnet. Bis die neue Seite bereit ist und ihren Tag erhalten hat, ziehen Requests kein Ticket; die alte Seite erhält `diag.gain {on:false}`. Damit ist das Übergangsfenster aus dem Review geschlossen: Ein Request der alten Seite nach `newRun()` wird nicht gelesen, eine Antwort auf einen vorher gezogenen Request trägt den alten Tag.
- Aus/Ein beginnt einen neuen Lauf mit neuem Tag; wartende Meldungen der Bridge werden beim neuen Tag verworfen.
- Grenze: Nach `newRun()` schweigt die Diagnose, bis eine Seite neu bereit wird. Bleibt die Seite nach einem Sitzungsverlust ohne Neuladen stehen, entstehen bis dahin keine Zeilen.
- Tests: Logout mit alter Antwort, alter Seitenmeldung und Request ohne bewaffnete Seite, danach neue Seite mit Zeilen (Kotlin 11); gepufferte Meldung nach Aus/Ein, auch mit Tag 0 (Kotlin 12); Seite weg samt Antwort auf ihren Request, danach neue Seite (Kotlin 13); neuer Tag verwirft Wartendes (JS 178); Suche vor neuer Bewaffnung (JS 301); Aus mit wartender Meldung (JS 381). Reload/Resume: Kotlin 13 und JS 170.

### 2.4 R4 – Kontext und Herkunft

- Jeder Kandidat (Titel × Datei) behält bis zu vier Beobachtungen, jeweils mit Gain-Status, Kontext des Titels in dieser Antwort, Rolle (`cur`/`queue`), Anfragefolge (`ans`) und Connect-Gerät (`local`/`remote`/`unknown`, aus dem vorhandenen `lastMedia.active`, ohne Abfrage).
- Seitenzeile: neueste Beobachtung nach Anfragefolge, dahinter jede abweichende (`|alt=`), Anzahl (`|n=`) und `here=same|other|unknown` gegen den Kontext, den die Seite selbst meldet. Die Anfragefolge ist nur ein Herkunftsmerkmal, kein Beleg für Aktualität auf Spotifys Seite; so steht es auch in der KDoc.
- Die Seite meldet ihren Kontext selbst und bei Kontextwechsel unter gleichem Titel mit `ev=context`.
- Antwortzeile: `answerCurrent` und `ctx` stammen nur vom Titel, auf den `updated_state_ref` zeigt; ohne eigenen Kontext steht `ctx=unknown`.
- Aliase: Eine Kennung, die eine gehaltene Zuordnung trägt, wird nie vergeben oder verdrängt. Verdrängt werden nur freie Aliase (höchstens 512 je Art), sichtbar als `aliasEvicted`.
- Tests: Kontextwechsel unter gleichem Titel (Kotlin 15, JS 215); aktueller Antworttitel ohne Kontext (Kotlin 16); Album −4 gegen spätere Playlist-Queue −7 in beiden Anfragereihenfolgen (Kotlin 17); verspätete Antwort nach Connect-Wechsel mit `dev=remote` und `dev=local` (Kotlin 14); späte Antwort nach Skip (Kotlin 10); Aliasstabilität nach 612 fremden Titeln (Kotlin 18); mehrere Titel einer Antwort (Kotlin 4).

### 2.5 R5 – Ausgabe-Positivliste

- Manifestgruppen: nur `file_ids_mp4` → `mp4` und `file_ids_mp4_dual` → `mp4_dual`. Andere `file_ids_*` werden nur als `otherLists` gezählt, ihr Name erscheint nicht.
- Kontext nur als festes Wort aus einer Liste (`album`, `playlist`, …, sonst `other`), Format nur als bis zu dreistellige Zahl, Datei-IDs nur als Lauf-Alias.
- Seitenfelder: Ereignis, `wrote` und `fault` nur aus festen Listen, Zahlen begrenzt, Strings sonst verworfen.
- `diag.gain` fängt alles ab und antwortet mit `{on, fault:'command'}` statt mit einem Fehler; der generische Resultatpfad sieht keinen freien Text. Kotlin protokolliert bei einem gescheiterten Aufruf nur „gain diagnostics: the page was not told“.
- Tests: Marker in Manifestname, Kontext, Seitenfeldern, `fault`, Token, Cookie und URL bis zur fertigen Zeile (Kotlin 28); werfender Getter beim Einschalten (JS 355); werfende Lese- und Suchzugriffe (JS 316, 344); fehlerhafte Seitenmeldungen (Kotlin 25). Die Zeilen laufen auf dem Gerät zusätzlich durch `LogRedactor`; die Tests prüfen die Zeile vor dem Redactor, also strenger.

### 2.6 R6 – Gleichheitstest

- `RecordingHost` zeichnet jeden Bridge-Aufruf als `Call(method, args)` mit den gesendeten JSON-Argumenten in Reihenfolge auf.
- Der Player läuft auf einem eigenen Single-Thread-Dispatcher. Jeder Schritt (bereit, Titel A, Diagnosemeldung und Titel B, Pause) beginnt erst, wenn 300 ms lang kein neuer Wiedergabebefehl kam. Abschluss: Pause angefordert, von der Seite gemeldet, danach erneut beruhigt. Kein fester Nachlauf mehr.
- Herausgenommen werden nur Lesebefehle (`media.state`, `volume.get`, `player.state`, `player.queue`, `device.activeNow`, `bridge.version`, `player.audioInfo`, `drm.probe`) und `diag.gain`. `diag.gain` muss bei Diagnose an genau einmal mit `on:true` und Tag kommen, bei aus nie.
- Empfindlichkeit: Der Vergleich schlägt bei vertauschten Befehlen, geändertem Argument und zusätzlichem Befehl fehl (Kotlin 30, an festen Folgen) und ebenso in echten Läufen, in die über einen Haken ein `media.volume` mit anderem oder zusätzlichem Argument eingeschleust wird (Kotlin 31). Die Produktionsimplementierung wurde dafür nicht geändert.
- Relay an/aus (offen): `ControlRelay` ruft die Diagnose erst nach `answer(...)` auf, innerhalb von `runCatching`, mit dem unveränderlichen `String` `sent`; `relayTicket()` vor dem Senden ändert nur Zähler der Diagnose. `onRelayAnswer` wirft nie, auch bei unlesbarer Antwort oder werfenden Quellen (Kotlin 26), und schreibt nichts zurück. Ein Test, der den Relay mit derselben Antwort bei Diagnose an und aus vergleicht, fehlt: `ControlRelay` hängt an WebView und OkHttp, `host-android` hat keine Testumgebung dafür (nur JUnit 4 und `org.json`). Ein solcher Test braucht entweder Robolectric oder das Herauslösen der Antwortaufbereitung in eine reine Funktion. Beides ist eine Änderung am Bestand, die ich nicht ohne Auftrag vornehme.

### 2.7 Nachrangige Punkte

| Punkt | Status | Umsetzung | Tests |
| --- | --- | --- | --- |
| Stabile Mess-IDs | behoben | siehe 2.4, `aliasEvicted` | Kotlin 18 |
| Loudness-Bedeutung | behoben | `loudSt=cached|unusable|not-read|unknown`; KDoc: NaN heißt „kein verwertbarer Cachewert, Ursache unbekannt“, nicht „Spotify hat keinen Wert“. Kein neuer Request, keine passive Zusatzerfassung | Kotlin 8 |
| Länge der Seitenzeilen | behoben | höchstens zwölf Kandidaten und höchstens 3.000 Zeichen dafür, Rest in `gainMore`; Zahlen über ±1e6 als `out-of-range`. Ungünstigster Fall (jeder Kandidat mit vier widersprüchlichen Beobachtungen) unter 4.000 Zeichen | Kotlin 20, 21 |
| Arbeit unter der Sperre | behoben | JSON-Parsing und Geräteabfrage außerhalb, nur die Aufnahme darin; je Antwort höchstens 512 Dateibeobachtungen (`capped`). Gleichzeitige große Antworten und Seitenmeldungen: nichts verloren, nichts geworfen | Kotlin 27, 20 |
| Einstellungstext | behoben | de: „… und notiert zusätzlich Lautstärkewerte der Wiedergabe im Protokoll (nur Beobachtung, der Klang bleibt unverändert) …“; en entsprechend | – |
| Gerätetests | offen | HK, siehe Abschnitt 6 | – |

## 3. Zeilenformat (Version 2)

Alle Zeilen beginnen mit `gain-diag run=<Lauf> seq=<Folge> ms=<seit Laufbeginn>` und enden mit `table= evicted= aliasEvicted= late= bad= merged=`.

- `ev=start reason=on|account v=2 max=256 perS=2 norm= boost= xf= appAdMute=`
- `ev=answer src=track-playback/state|devices|other ans=<Anfragefolge> answerCurrent=t<n>|ad|unknown ctx=<Kontext des aktuellen Titels> device=local|remote|unknown tracks= ads= entries=<bis 12> more= otherLists= capped=`
  Eintrag: `t1/cur/playlist:f1@10/mp4:candidate=-5.1271`; ohne Datei-ID `?@10/mp4:unknown=…`; Gain `null`, `absent`, `invalid` oder Zahl.
- `ev=enable|track|context|player|element|volume track= kind= ctx= pl= el= device= pv= pvSt=ok|absent|invalid|error cubic= vol= muted= paused= level= routed= pageAdMute= wrote=level|restore|mute|none wroteMs= wroteMatch= norm= boost= xf= appAdMute= loud= loudSt= gain=<Kandidaten> gainMore= pageMerged= pageFaults= pageFault=`
  Kandidat: `f1@10/mp4:candidate=-4.0000(ans=2;role=cur;ctx=album;dev=local;here=same)|alt=-7.0000(ans=1;role=queue;ctx=playlist;dev=local;here=other)|n=2`.

Unverändert: `candidate` statt `selected`, keine Gain-Anwendung, keine Spotify-IDs, Tokens, Cookies oder URLs.

## 4. Nachweise

### 4.1 Lokal (Sitzung ohne Gradle)

- `node --test base/api-spotify/bridge-tests/gain-diagnostics.test.mjs`: 19/19 bestanden (Node 22). Gegen die Bridge von `82efcaa` schlagen die neuen Fälle fehl.
- `python3 .github/scripts/test_ui_release_notes.py`: bestanden.

### 4.2 CI

| | |
| --- | --- |
| Lauf | [43388148077](https://github.com/kvmgithub/Spotilol/actions/runs/43388148077) (`workflow_dispatch` auf `work/gain-diagnostics`, Commit `2329f0f9a73b88db142d2fff2f29c333fa1f1bdb`) |
| Ergebnis | erfolgreich (Job `build`: success; Release-Notes übersprungen, keine Veröffentlichung). Vorlauf [43387905777](https://github.com/kvmgithub/Spotilol/actions/runs/43387905777) auf `aa4ab33` scheiterte beim Kompilieren der Tests am Testnamen mit `:` |
| JUnit | Gradle-Schritt „Unit tests and signed release“ mit allen Unit-Tests: `BUILD SUCCESSFUL in 4m 43s`, ein fehlgeschlagener Test hätte den Lauf abgebrochen. Die Fallzahlen stehen nur in den Testberichten (Artefakt unten), nicht im Job-Log; erwartet sind 31 Fälle `GainDiagnosticsTest` |
| Node in der CI | eigener Schritt vor Gradle: 19 Tests, 19 bestanden, 0 fehlgeschlagen |
| APK-Artefakt | [apk-2329f0f9a73b88db142d2fff2f29c333fa1f1bdb](https://github.com/kvmgithub/Spotilol/actions/runs/43388148077/artifacts/14020101100), enthält APK, `SHA256SUMS.txt`, `signers.txt`, `apk-metadata.txt`, `build-info.txt`. CI-Prüfungen bestanden: genau ein Signierer, `aapt`: Label MySpotilol, nicht debuggable, Version 1.4.0 / 1004000 |
| APK SHA-256 | `6a534b87bb161eeb473af5df7bad540b0850b8a3f7b40518196ffdc5e96caf7c` |
| Signierer SHA-256 | `73cf309a74b1a107a1e1cbb9453806f7b0d8e803957ecd371cb51cfed0b21e9a` |
| Testberichte | [test-reports-2329f0f9a73b88db142d2fff2f29c333fa1f1bdb](https://github.com/kvmgithub/Spotilol/actions/runs/43388148077/artifacts/14019731741), JUnit-XML und Node-TAP |

Die Artefakte kann ich in dieser Sitzung nicht herunterladen (Blob-Zugriff gesperrt); Prüfsumme, Signierer und Testzahlen stammen aus dem Job-Log desselben Laufs.

### 4.3 Testzuordnung

Kotlin `GainDiagnosticsTest` (Reihenfolge in der Datei):

1. off by default it reads nothing, writes nothing and sets no timer
2. turned on it starts a run and says the app's sound settings once
3. two formats of one song keep their own gain, as offered candidates, not as the file played
4. several songs of one answer keep their own ids and places, the answer's current one is only the answer's
5. gain null, absent, invalid and zero stay apart
6. a file without an id is unknown, not a candidate
7. ads are counted, not kept
8. loudness is read from what the player holds, never asked for, and an unusable value is not called Spotify's
9. the page's raw player volume, missing, invalid or unreadable, is said as such
10. a late answer after a skip never becomes the current song's gain
11. after a logout nothing of the old run or page is taken, neither answers nor page messages
12. a message buffered under the old arming is dropped after off and on
13. a page that went away is taken no further, nor are answers to its requests
14. another device playing is said, on the page line and with each answer's own
15. the page's context is its own, also when only the context changes
16. an answer's current song without a context says unknown, never another song's
17. a later answer from another context does not pass as the page's, both values stay visible
18. ids held by an assignment are never given away, however many songs pass
19. 257 assignments keep the newest 256
20. one answer is read up to its bound, a page line names a bounded number of candidates
21. a page line stays whole in Logcat, also with every candidate in conflict
22. a flood of events gives at most two lines a second, the rest merged and counted
23. answers and page events wait apart, a flood of one never hides the other
24. turning off with a line waiting drops it, a new run never shows it
25. malformed page messages are counted and never written, later ones still are
26. an unreadable answer or a failing source never reaches the caller
27. a large answer and page messages at the same time, nothing lost, nothing thrown
28. no token, cookie, URL, Spotify id or unknown manifest name reaches a line
29. with the diagnostics on the player sends the same playback commands, arguments and order as with them off
30. the comparison catches swapped commands, changed arguments and an added command
31. the comparison catches a command added or changed in a real run

Dazu unverändert aus M1: `BridgeMessagesTest`, zwei Fälle zum `diag`-Abzweig.

JS `gain-diagnostics.test.mjs` (Zeile des Testbeginns): 130 aus per Vorgabe; 142 bewaffnet; 162 ohne Tag nicht bewaffnet; 170 gleicher Tag (Resume); 178 neuer Tag verwirft Wartendes; 191 Lautstärke mit eigener Schreibmarke; 204 Stummschaltung für Werbung; 215 Titel- und Kontextwechsel; 230 neuer SDK-Player; 250 kein Element; 268 Player erscheint später; 284 veraltete Suche; 301 Suche vor neuer Bewaffnung; 316 werfender Diagnose-Read (R1); 344 werfende SDK-/Elementzugriffe; 355 werfender Getter beim Einschalten (R5 Pfad B); 366 Flut; 381 Aus mit wartender Meldung; 395 an/aus gleiche Meldungen und Pegel.

## 5. Grenzen und offene Punkte

- R6 Relay an/aus auf Android-Ebene: offen, siehe 2.6.
- Nach `newRun()` keine Zeilen, bis eine Seite neu bereit wird (2.3).
- Gerätezuordnung aus dem vorhandenen `lastMedia.active`; dessen Frische ist nicht garantiert, unbekannt steht als `unknown`.
- Unverändert aus M1 und im Review akzeptiert: keine Dealer-Nachrichten, kein `fetch(Request)`-Pfad, keine Reparatur des `boost.routed`-Verhaltens, unmarkierte Guard-Schreibungen bleiben Beobachtungslücke.
- `ownLevel` liest `_playerVolume` im Bestandscode ungeschützt (2.1), nicht Teil von M1.

## 6. Gerätetest (HK)

Mit der APK aus 4.2, Diagnoseprotokoll in den Einstellungen:

1. Aus: einige Titel abspielen, Protokoll exportieren. Keine Zeile `gain-diag`.
2. An: Titelwechsel, Lautstärke ändern, Pause/Fortsetzen, Hintergrund, Sperrbildschirm, Rückkehr. Zeilen `ev=answer` und `ev=track|volume|…` mit `pvSt=ok`, Wiedergabe und Lautstärke unverändert gegenüber 1.
3. Kontextwechsel: denselben Titel einmal aus einer Playlist, einmal aus dem Album starten. Seitenzeile mit `ctx=album`, Kandidaten mit `here=`.
4. Neu laden der Seite (App-Neustart) und Abmelden/Anmelden: danach neue Zeilen mit höherem `run` oder neuem Tag, `late` darf steigen, keine Werte des alten Laufs.
5. Abschalten: keine weiteren Zeilen.
6. Export: Zeilen enthalten keine `spotify:`-Kennungen, Datei-IDs, Tokens oder URLs.
