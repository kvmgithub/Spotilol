# MySpotilol M1 – Übergabe 3 an ChatGPT/Codex (Korrekturen nach Review 2)

Stand: 13. November 2026. Antwort auf „MySpotilol – M1, unabhängiges Review 2“ (`M1-05-review-2.md`). Rollen unverändert.

**Ergebnis:** Pakete A, B und C sowie die Punkte aus Abschnitt 6 sind umgesetzt; der Relay-Vergleich läuft jetzt am Produktionscode. @@CIRESULT@@ Offen bleibt HKs Gerätetest. Nichts gemergt, nichts veröffentlicht, `myspoti-ui` unberührt.

## 1. Stände

| | |
| --- | --- |
| Ausgangspunkt | Spitze `17ecb75` (Code `2329f0f`, zwei Dokumentcommits `ddf6f00`, `17ecb75`); unverändert seit Review 2, Dokumentcommits erhalten |
| **Code-Ergebnis** | **`@@RESULT@@`** (ein Commit: „Gain diagnostics: review 2 (search origin, page of every line, relay test seam)“) |
| Dokumentcommits danach | `@@DOCS@@`: nur `docs/agent-handoff/` (Review 2 abgelegt, diese Übergabe). Auf dem Arbeitszweig startet die CI nur per Hand; APK-Code ist das Code-Ergebnis. |
| Diff Korrektur | https://github.com/kvmgithub/Spotilol/compare/2329f0f9a73b88db142d2fff2f29c333fa1f1bdb...@@RESULT@@ |
| Diff gegen Basis | https://github.com/kvmgithub/Spotilol/compare/64f97bb9ffe517b2f40ded45b7e934e23ec6a11a...@@RESULT@@ |
| Produktbranch | `myspoti-ui` weiter `63bff95` (nur README seit `64f97bb`), keine Überschneidung |

Geänderte Dateien (7): `player-bridge.js`, `GainDiagnostics.kt`, `ControlRelay.kt`, neu `RelayAnswers.kt`, `gain-diagnostics.test.mjs`, `GainDiagnosticsTest.kt`, neu `RelayAnswersTest.kt`.

## 2. Befundstatus

| Punkt | Status | Tests |
| --- | --- | --- |
| A1 Alte erfolgreiche Suche nach SDK-Wechsel ohne Status-Push | behoben | JS 327 |
| A2 Alte abgelehnte Suche nach Aus/Ein | behoben | JS 348 |
| A3 Ablehnung der aktuellen Suche bleibt fester Code | behoben | JS 348 (zweiter Teil) |
| B Seitenherkunft über Drosselung und Reload | behoben | Kotlin 16, 11; JS 162 |
| C Relay-Vergleich am Produktionspfad (fünf Fälle der Tabelle) | behoben | RelayAnswers 1–6; JS 495 |
| 6.1 Geräteangabe als Empfangssnapshot, verspäteter Fall | behoben | Kotlin 15, 14 |
| 6.2 `here=same` heißt gleiche Kontextart | behoben (Dokumentation) | – |
| 6.3 Begrenzte Beobachtung | behoben (Dokumentation) | – |
| Gerätetest | offen (HK) | – |

R1, R4 und R5 bleiben wie in Review 2 bestätigt; die bestehenden Tests dazu laufen unverändert mit.

### 2.1 Paket A – Abschluss einer Playersuche

`gdLocate` wertet Erfolg und Ablehnung über dieselbe Prüfung aus (`settled`):

1. Gehört die Suche nicht zur aktuellen Bewaffnung oder ist sie nicht mehr die Suche, auf die die Bridge wartet (`gen`, `gd.promise`), passiert nichts, auch kein Fehlerzähler.
2. Hält das SDK inzwischen ein anderes Promise (ersetzt ohne Statusereignis dazwischen), wird dieses übernommen und gesucht; das Ereignis (`enable` oder `player`) wird für den neuen Player gemeldet. Die abgelöste Suche bindet nie.
3. Nur sonst: Ablehnung erhöht `faults` mit `fault=locate`, Erfolg bindet Player und Element.

Keine Pollingschleife, keine Timer, keine Requests. Gegenproben des Reviews als Tests: JS 327 (Suche A offen, SDK ersetzt durch B, A löst auf: A ohne Listener, B gebunden mit `pv=0.3`, `vol=0.25`, `cubic=false`, nie `pv=0.8`; folgendes Statusereignis ändert nichts), JS 348 (alte Suche unter Tag 1 nach Aus und Tag 2 abgelehnt: Lautstärkezeile unter Tag 2 mit `faults=0`; danach scheitert die aktuelle Suche: `faults=1 fault=locate`, ohne Fehlertext). Beide Tests schlagen gegen die Bridge von `2329f0f` fehl.

### 2.2 Paket B – Seitenherkunft

- Jede Seitenzeile trägt `page=<Tag>`, jede Antwortzeile `page=<Tag der anfragenden Seite>`, jede Kandidatenbeobachtung `page=` in ihrer Klammer. Der Wert steht fest, wenn die Zeile angenommen wird; eine gedrosselte Zeile behält ihn.
- Wartende Zeilen sind nach Art und Seite getrennt (`page:<Tag>`, `answer:<Tag>`). Zwei Seiten werden nie zu einer Zeile zusammengefasst; die globale Grenze von zwei Zeilen je Sekunde bleibt.
- Ein Reload leert keine Kandidaten derselben Sitzung; Laufwechsel und Aus/Ein verwerfen wie bisher.
- Tags werden je App-Prozess nie wiederverwendet. Nach einem Prozessneustart beginnen sie wieder bei 1; dann unterscheidet `run` bzw. die Startzeile.
- Formatversion in der Startzeile jetzt `v=3`.
- Test Kotlin 16 (Abnahmefall des Reviews): Zeile von Seite 1 gedrosselt, Reload, Seite 2 meldet wieder `pl=1`/`el=1`; ausgegeben werden `1:track`, `1:volume`, `2:track`, jeweils `merged=0`.

### 2.3 Paket C – Relay

**Testnaht:** Der letzte Schritt jeder weitergereichten Antwort steht jetzt in `RelayAnswers` (host-android): Header ohne Set-Cookie, AdBlock-Filter mit seinen Logzeilen, `adAhead`, genau ein Aufruf der Antwort an das anfragende Dokument, danach die Diagnose in `runCatching`. `ControlRelay` ruft ihn mit seinem `answer(reply, id, …)` auf; der Code wurde verschoben, nicht geändert. `RelayAnswers` hat keinen HTTP-Client.

**Herkunft am Relay-Eingang:** Die Bridge legt ihren Tag in `window.__mspGainTag` ab (0 bei aus). Der Fetch-Wrapper des Relays (`ControlRelay.SCRIPT`) liest ihn bei jedem weitergeleiteten Request in das Feld `gain` der Nachricht an den Host; an Spotify geht davon nichts. `RelayAnswers.ticket` zieht das Ticket aus diesem Feld: nur der Tag der aktuell bewaffneten Seite erhält ein Ticket; ein anderer Tag zählt `late`, Tag 0 bleibt ungelesen. Damit hängt die Zuordnung an der Seite, die den Request gestellt hat, nicht an der zum Empfangszeitpunkt bewaffneten.

| Fall des Reviews | Test |
| --- | --- |
| Gleiche Antwort, Diagnose aus/an: Status, Header, bytegleicher Body, genau eine Antwort | RelayAnswers 1 (dazu 2: nur erfolgreiche Track-Playback-Antworten werden gelesen) |
| Diagnose scheitert kontrolliert (Ticket und Auswertung) | RelayAnswers 3 |
| AdBlock-Antwort: Filter aus/an gleich, Diagnose sieht die weitergereichte Fassung | RelayAnswers 4 |
| Reload und neue Aktivierung: altes Ticket verworfen, Antwort an das alte Dokument | RelayAnswers 5 |
| Gepufferter Request der alten Seite | RelayAnswers 6 (Relay-Eingang Kotlin), JS 495 (Fetch-Wrapper aus `ControlRelay.kt` im VM: Tag 0, 5, ungültig, werfender Getter; Antwort unverändert zurück), JS 162 (Bridge setzt und löscht den Tag) |

Grenze: Die WebView-Zustellung selbst (`WebMessageListener`, `JavaScriptReplyProxy`, Main-Thread-Post) ist nicht im JVM-Test; dass `answer()` die Nachricht auf den Main-Thread stellt, heißt nicht, dass die Seite sie schon hat. Die Tests belegen Inhalt und Anzahl der übergebenen Antwort und die Reihenfolge Antwort vor Diagnose.

### 2.4 Abschnitt 6

- **Gerät:** Beobachtungen heißen jetzt `rxDev`, die Antwortzeile `rxDevice`: der Host-Cache (`lastMedia.active`) beim Empfang der Antwort, kein Anfragezustand, kein Nachweis seiner Frische oder der serverseitigen Geräteherkunft. Test Kotlin 15: Ticket bei `active=true`, Wechsel, Antwort 2, dann die verspätete Antwort 1; beide `rxDev=remote`. `device=` in der Seitenzeile ist der Cache zum Zeitpunkt der Seitenmeldung. Keine neue Connect-Abfrage.
- **`here`:** vergleicht die Kontextart (`album`, `playlist`, …), nicht die Kontext-ID; zwei verschiedene Playlists ergeben `here=same`. So in der KDoc.
- **Grenzen:** `evicted`, `capped` und `gain=none` sind Grenzen der Beobachtung, kein Beleg für „Spotify lieferte keinen Gain“. So in der KDoc. Keine Priorisierung des aktuellen Titels und keine Zusammenfassung gleicher Beobachtungen in dieser Runde.
- **`settle()`:** bleibt eine Ruheheuristik mit zusätzlicher konkreter Abschlussbedingung (angeforderte und gemeldete Pause), wie in Review 2 eingeordnet.

## 3. Zeilenformat v=3 (Änderungen)

- Antwort: `ev=answer page=<Tag> … rxDevice=local|remote|unknown …`
- Seite: `ev=… page=<Tag> track= … device= …`
- Kandidat: `f1@10/mp4:candidate=-4.0000(ans=2;page=1;role=cur;ctx=album;rxDev=local;here=same)|alt=…|n=2`

Alles Übrige wie in Übergabe 2, Abschnitt 3.

## 4. Nachweise

- Lokal: `node --test base/api-spotify/bridge-tests/gain-diagnostics.test.mjs` 23/23 (Node 22); `python3 .github/scripts/test_ui_release_notes.py` bestanden.

| CI | |
| --- | --- |
| Lauf | @@RUN@@ |
| Ergebnis | @@CI@@ |
| Node | @@NODE@@ |
| APK | @@APKART@@ |
| APK SHA-256 | `@@APKSHA@@` |
| Signierer | `@@SIGNER@@` |
| Testberichte | @@REPORTS@@ |

JUnit-Fallzahlen stehen nur in den Testberichten; Artefakte kann ich hier nicht herunterladen. Erwartet: `GainDiagnosticsTest` 33, `RelayAnswersTest` 6.

### 4.1 Testzuordnung

Kotlin `GainDiagnosticsTest`: 1 off by default · 2 start · 3 zwei Formate · 4 mehrere Titel · 5 Gain-Status · 6 Datei ohne ID · 7 Werbung · 8 Loudness · 9 Rohwert pv · 10 späte Antwort nach Skip · 11 Logout · 12 Aus/Ein · 13 Seite weg · 14 Gerätewechsel · **15 verspätete Antwort nach Gerätewechsel** · **16 gedrosselte Zeile über Reload** · 17 Kontextwechsel · 18 aktueller Titel ohne Kontext · 19 widersprüchliche Kontexte · 20 Aliase · 21 257 Zuordnungen · 22 Antwortgrenze · 23 Logcat-Länge · 24 Flut · 25 getrennte Warteschlangen · 26 Aus mit wartender Zeile · 27 fehlerhafte Seitenmeldungen · 28 unlesbare Antwort · 29 Nebenläufigkeit · 30 Datenschutz · 31–33 Befehlsvergleich mit Negativkontrollen.

Kotlin `RelayAnswersTest` (host-android, JUnit 4): 1 gleiche Antwort aus/an · 2 nur erfolgreiche Track-Playback-Antworten · 3 scheiternde Diagnose · 4 AdBlock · 5 Reload, altes Ticket · 6 Request der alten Seite.

JS (Zeile des Testbeginns): 162 Tag für den Relay · 327 ersetzte Suche · 348 abgelehnte alte Suche · 495 Fetch-Wrapper des Relays; übrige wie Übergabe 2.

## 5. Gerätetest (HK)

Den installierten APK-Stand über die Prüfsumme aus Abschnitt 4 festhalten; die Versionsanzeige 1.4.0 unterscheidet die Arbeitszweig-Builds nicht.

1. Diagnose aus, dann an: Titelwechsel, Lautstärke, Pause/Fortsetzen, Hintergrund, Sperrbildschirm, Rückkehr. Wiedergabe und Lautstärke gleich.
2. Gleichen Titel aus Playlist und aus Album starten: `ctx=` der Seitenzeile, `ctx=` und `here=` der Kandidaten.
3. Reload (App in den Hintergrund und Seite neu laden lassen, oder App-Neustart) getrennt von Ab-/Anmeldung: nach Reload neuer `page=`-Wert bei gleichem `run`; nach Abmelden höherer `run`.
4. Abschalten: keine neuen `gain-diag`-Zeilen; ältere bleiben im Export.
5. Export: die `gain-diag`-Zeilen ohne `spotify:`-Kennungen, Datei-IDs, Tokens, URLs. Fehlende Playerfelder dürfen als `absent`, `invalid` oder `error` erscheinen.
