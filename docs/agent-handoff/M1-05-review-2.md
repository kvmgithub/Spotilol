# MySpotilol – M1, unabhängiges Review 2

Stand: 13. November 2026. Geprüft wurden Claudes zweite Übergabe, die beiden Korrekturcommits, die CI-Berichte und die signierte APK.

**Urteil: Die Überarbeitung behebt den Großteil des ersten Reviews. M1 ist noch nicht vollständig abnahmebereit.** R1 und R5 sind für die beanstandeten Fehlerpfade erledigt. Die ursprünglichen Kontextfehler aus R4 sind korrigiert. Bei R2 und R3 bleiben begrenzte Fehler der Diagnosezuordnung; bei R6 fehlt weiterhin der bereits angeforderte Relay-Vergleich.

Die verbleibenden Arbeiten betreffen die Verlässlichkeit der Messdaten und ihren Nachweis. In diesem Review wurde weder eine reale Wiedergabestörung auf dem Telefon noch die Ausgabe echter Zugangsdaten festgestellt. Ein Neuaufbau der Diagnose ist nicht erforderlich.

Codex hat ausschließlich gelesen und separate Prüfprogramme ausgeführt. Repository-Quellen, Branches, Releases und GitHub-Kommentare wurden nicht verändert. Claude bleibt Implementierer; Codex prüft die nächste Übergabe. HKs Gerätetest und Integrationsentscheidung bleiben getrennte Schritte.

## 1. Geprüfter Stand

| Gegenstand | Nachweis |
| --- | --- |
| Repository | [kvmgithub/Spotilol](https://github.com/kvmgithub/Spotilol) |
| Basis des ersten Reviews | `82efcaa3eb1de21be85fc7dd8b6016a8eff260cd` |
| Neuer Code-Stand | **`2329f0f9a73b88db142d2fff2f29c333fa1f1bdb`** |
| Korrekturdiff | [Zwei Commits, zehn Dateien, +934/−367 Zeilen](https://github.com/kvmgithub/Spotilol/compare/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd...2329f0f9a73b88db142d2fff2f29c333fa1f1bdb) |
| Tatsächliche Spitze von `work/gain-diagnostics` | `17ecb755d6e8a6fd77b759649cc256e7a9eb39fd`. Die zwei nachfolgenden Commits ändern ausschließlich vier Dokumente unter `docs/agent-handoff/`. Der geprüfte APK-Code bleibt `2329f0f`. |
| Produktbranch `myspoti-ui` | `63bff9587c7f3de2a258cd8b8278b0c6e64d9994`; gegenüber der ursprünglichen Produktbasis `64f97bb` nur eine README-Änderung. M1 ist dort nicht integriert. |
| Quellkontrolle | Die zehn untersuchten Änderungsdateien stimmen mit den Git-Blob-Hashes des Ergebniscommits überein. |
| CI | [Lauf 43388148077](https://github.com/kvmgithub/Spotilol/actions/runs/43388148077): Build erfolgreich; Veröffentlichung übersprungen. |
| Originale JUnit-Berichte | Heruntergeladen und ausgewertet: **446 Tests in 71 Suiten, null Fehler, null Fehlschläge, null übersprungene Tests**. Davon **31 Tests in `GainDiagnosticsTest`**. |
| JavaScript | 19 Tests in CI; hier mit Node 24.22.1 erneut **19/19 bestanden**. |
| Release-Notes-Prüfung | Hier erneut erfolgreich ausgeführt. |
| Zusätzliche Gegenproben | Unverändertes Produktions-JavaScript in der bestehenden VM-Testumgebung; unverändertes `GainDiagnostics.kt` mit Kotlin 2.4.0 und kotlinx.serialization 1.11.0 in separater Prüfumgebung. |
| Grenzen | Kein eigener Android-Gerätetest und kein vollständiger lokaler Gradle-Build. Die vollständigen Android-/Kotlin-Testergebnisse stammen aus dem überprüften CI-Lauf. |

### APK

[Artefakt des geprüften Commits](https://github.com/kvmgithub/Spotilol/actions/runs/43388148077/artifacts/14020101100), Datei `MySpotilol-arm64-1.4.0.apk`.

| Prüfung | Ergebnis |
| --- | --- |
| Selbst berechnete APK-SHA-256 | `6a534b87bb161eeb473af5df7bad540b0850b8a3f7b40518196ffdc5e96caf7c` |
| Zuordnung | Prüfsumme stimmt mit Übergabe und `SHA256SUMS.txt` überein; `build-info.txt` nennt Commit `2329f0f` und Lauf `43388148077`. |
| Eingebettete Bridge | `bridge/player-bridge.js` in der APK ist bytegleich mit der Datei des geprüften Commits. |
| Signierer | CI-Prüfung und `signers.txt`: genau der vorgesehene Signierer, SHA-256 `73cf309a74b1a107a1e1cbb9453806f7b0d8e803957ecd371cb51cfed0b21e9a`. Keine zusätzliche lokale `apksigner`-Prüfung. |
| Metadaten | `com.project.lol.fork.ui`, Version 1.4.0 / Code 1004000, nicht debuggable; keine Installer-Berechtigung. |
| Inhalt | Nur `arm64-v8a`-Bibliotheken. Der bestehende Ausschlussscan wurde an den heruntergeladenen DEX-Dateien wiederholt: keine Treffer für die konkret ausgeschlossenen Komponenten. Das ist kein allgemeiner Telemetriefreiheitsnachweis. |

Die [Testberichte](https://github.com/kvmgithub/Spotilol/actions/runs/43388148077/artifacts/14019731741) und die APK waren in dieser Reviewsitzung tatsächlich herunterladbar.

## 2. Status gegenüber Review 1

| Punkt | Bewertung |
| --- | --- |
| **R1 – Fehlerisolation** | **Behoben für die beanstandeten Pfade.** Der zusätzliche Diagnosezweig fängt Lesefehler ab; die Gegenprobe mit werfendem `_playerVolume` lässt die regulären Statusmeldungen passieren. Anschließend werden wieder gültige Werte erfasst. |
| **R2 – Player und Element** | **Weitgehend behoben.** Neuer Player nach Statusereignis, fehlendes Element und später erscheinender Player funktionieren. Die asynchrone Rückbindung hat noch einen Restfall, siehe Abschnitt 3. |
| **R3 – Lauf und Seite** | **Teilweise behoben.** Alte Tags und Tickets nach Laufwechsel werden verworfen; das nicht aktivierte Übergangsfenster akzeptiert keine Relay-Beobachtungen. Gepufferte Ausgaben und Seitenidentität sind noch nicht durchgängig behandelt, siehe Abschnitt 4. |
| **R4 – Kontext und Herkunft** | **Die ursprünglichen drei Gegenproben sind abgedeckt.** Kontextwechsel desselben Titels wird erfasst; der aktuelle Antworttitel übernimmt keinen Queue-Kontext; widersprüchliche Kandidaten behalten Anfragefolge und Kontext. Die Geräteangabe benötigt eine präzisere Beschreibung und einen tatsächlich verspäteten Test, siehe Abschnitt 6. |
| **R5 – Positivliste** | **Die beiden beanstandeten Wege sind geschlossen.** Unbekannte Manifestnamen werden nur gezählt; die getesteten JavaScript-Diagnosefehler übertragen keinen freien Ausnahmeinhalt. Keine pauschale Aussage über sämtliche vorhandenen Logpfade. |
| **R6 – Gleichheitsnachweis** | **Deutlich verbessert, weiterhin unvollständig.** Methode, Argumente und Reihenfolge werden verglichen; Negativkontrollen erkennen Änderungen. Der Relay-Eingabe-/Ausgabe-Vergleich fehlt. |

Weitere bestätigte Korrekturen: gehaltene Zuordnungen behalten ihre Aliase; Aliasverdrängungen werden gezählt; `loudSt=unusable` behauptet keinen fehlenden Spotify-Wert; Kandidatenausgaben und Zahlen sind begrenzt; die Einstellungstexte beschreiben die Diagnose. Parsing und Ermittlung des Gerätestatus erfolgen beim Relay außerhalb der gemeinsamen Sperre.

## 3. Restpaket A: Abschluss einer Playersuche muss ihre aktuelle Herkunft prüfen

**P2 für die Diagnosekorrektheit; Fortsetzung von R2.**

Fundstelle: [`gdLocate`, player-bridge.js:360](https://github.com/kvmgithub/Spotilol/blob/2329f0f9a73b88db142d2fff2f29c333fa1f1bdb/base/api-spotify/src/main/resources/bridge/player-bridge.js#L360).

Der Erfolgszweig prüft die Generation und `promise !== gd.promise`. `gd.promise` wird jedoch erst beim nächsten Statusereignis mit dem SDK abgeglichen. Damit wird nicht unmittelbar geprüft, ob das SDK die aufgelöste Promise überhaupt noch hält.

**Ausgeführter Ablauf am Produktions-JavaScript:**

1. Diagnose mit Tag 1 aktivieren; SDK-Playersuche A bleibt zunächst offen.
2. Das SDK ersetzt seine Promise durch B. Noch kein weiteres PlayerAPI-Statusereignis auslösen.
3. Die alte Suche A auflösen.

| Beobachtung | Ergebnis |
| --- | --- |
| Erneut gebundene Messquelle | Alter Player A |
| Ausgegebene Pegel | `pv=0.8`, `vol=1` |
| Werte des inzwischen im SDK referenzierten B | `pv=0.3`, `vol=0.25` |
| Listener | A: 1; B: 0 |
| Kontrollschritt: nächstes Statusereignis | Erst dann Wechsel auf B und dessen Werte |

Das belegt eine vorübergehend veraltete Messquelle. Es belegt nicht, welcher Player in diesem synthetischen Ablauf tatsächlich hörbares Audio ausgeben würde.

**Korrekturziel:** Vor dem Binden die aktuelle SDK-Identität erneut prüfen oder eine gleichwertige Bedingung verwenden. Eine abgelöste Suche darf nicht als aktuelle Messquelle zurückkehren, auch wenn noch kein weiteres Statusereignis eingetroffen ist. Dafür sind keine neue Pollingschleife und keine neuen Requests nötig.

**Zweiter, kleinerer Restfall im selben Abschlusszweig:** Der Ablehnungshandler ruft unmittelbar `gdFault('locate')` auf, ohne Generation oder Aktivierung zu prüfen.

Gegenprobe: Suche unter Tag 1 starten → ausschalten → Tag 2 mit gesundem Player aktivieren → alte Suche ablehnen → neue Lautstärkezeile auslösen. Ergebnis unter **Tag 2**:

```text
vorher: page faults = 0
danach: faults=1 fault=locate
```

Der Fehler der alten Suche wird dem neuen Messlauf zugerechnet. Der kontrollierte freie Fehlertext wurde dabei **nicht** ausgegeben; dies öffnet R5 nicht erneut.

**Abnahme für Paket A:**

- Alte erfolgreiche Suche nach SDK-Wechsel ohne dazwischenliegenden Status-Push bindet nicht.
- Alte abgelehnte Suche nach Aus/Ein verändert keine Fehlerzähler des neuen Tags.
- Eine Ablehnung der tatsächlich aktuellen Suche wird weiterhin als fester Fehlercode erfasst.
- Bestehende Playerwechsel-, Listener-, Fehlerisolation- und An/Aus-Tests bleiben grün.

## 4. Restpaket B: Seitenwechsel auch in gepufferten Ausgaben eindeutig machen

**P2 für die Zuordnung der Messwerte; Fortsetzung von R3.**

Fundstellen: [`pageGone` und `armPage`, GainDiagnostics.kt:152](https://github.com/kvmgithub/Spotilol/blob/2329f0f9a73b88db142d2fff2f29c333fa1f1bdb/base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt#L152), [Ausgabepuffer:319](https://github.com/kvmgithub/Spotilol/blob/2329f0f9a73b88db142d2fff2f29c333fa1f1bdb/base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt#L319), [Seitenzeile:257](https://github.com/kvmgithub/Spotilol/blob/2329f0f9a73b88db142d2fff2f29c333fa1f1bdb/base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt#L257).

`pageGone()` setzt nur `armed=0`. Schon akzeptierte, wegen der Zeilenbegrenzung wartende Kotlin-Ausgaben bleiben im Puffer. `armPage()` vergibt einen neuen Tag, berücksichtigt diese Ausgaben aber nicht. Der Tag wird außerdem in den fertigen Diagnosezeilen nicht ausgegeben.

**Ausgeführte Kotlin-Gegenprobe:**

1. Diagnose einschalten und Seite A aktivieren.
2. Startzeile und erste Seitenmeldung füllen das Zeitfenster; eine weitere Lautstärkezeile wartet.
3. `pageGone()` aufrufen, anschließend Seite B aktivieren.
4. Die Testuhr um eine Sekunde vorrücken.

Danach erscheint noch die zuvor gepufferte Zeile:

```text
gain-diag run=1 seq=2 ms=0 ev=volume track=t2 ... el=1 ... late=0
```

**Präzise Einordnung:** Diese Meldung wurde vor dem Seitenwechsel akzeptiert. Sie behält ihren alten `ms`- und `seq`-Wert. Das ist kein Beweis, dass eine verspätet eingetroffene alte Meldung die neue Tag-Prüfung umgeht. Diese Prüfung funktioniert in den vorhandenen Tests.

Es fehlt jedoch die Ursprungsseite in der Ausgabe. Nach einem echten Reload beginnen die JavaScript-IDs für Player und Element wieder bei 1; der Kotlin-Messlauf muss dabei nicht wechseln. Gleiche `run/pl/el`-Werte können deshalb unterschiedliche reale Objekte bezeichnen. Zusammen mit nachlaufenden Pufferzeilen erschwert das genau die Player-/Elementzuordnung, die M1 liefern soll.

**Korrekturziel:**

- Seitenherkunft im exportierten Format erhalten, beispielsweise durch einen festen numerischen `page`-Tag; keine URL und keine Spotify-ID.
- Wartende alte Seitenzeilen beim Wechsel entweder verwerfen oder ausdrücklich mit ihrer ursprünglichen Seitenkennung ausgeben. Die Kennung muss beim Erfassen feststehen, nicht erst beim späteren Schreiben.
- Die globale Zeilenbegrenzung beibehalten. Keine zusätzliche Wiedergabe- oder Netzwerkaktion auslösen.
- Ein bloßer Reload erfordert dadurch nicht automatisch das Leeren aller Kandidaten derselben Sitzung.

**Abnahme für Paket B:** Gedrosselte Meldung vor Reload, neue Seite mit erneut `pl=1/el=1`, anschließende Ausgabe. Jede ausgegebene Beobachtung muss eindeutig ihrer Seite zugeordnet werden können. Laufwechsel und Aus/Ein müssen alte Beobachtungen weiterhin verwerfen.

**Noch zu prüfende Integration, keine behauptete Gerätebeobachtung:** [ControlRelay.kt:163](https://github.com/kvmgithub/Spotilol/blob/2329f0f9a73b88db142d2fff2f29c333fa1f1bdb/base/host-android/src/main/kotlin/app/myspoti/host/android/ControlRelay.kt#L163) zieht das Ticket aus der global gerade aktivierten Diagnose. Der [Nachrichtenkanal](https://github.com/kvmgithub/Spotilol/blob/2329f0f9a73b88db142d2fff2f29c333fa1f1bdb/base/host-android/src/main/kotlin/app/myspoti/host/android/PlayerChannel.kt#L25) prüft Origin und Hauptframe; dem Ticket wird keine Herkunftskennung der konkreten Nachricht übergeben. Die tatsächliche WebView-Zustellreihenfolge eines gepufferten Alt-Requests nach erneuter Aktivierung ist hier nicht reproduziert worden. Die Übergabe sollte die bisher belegte Garantie daher auf alte Tickets und das deaktivierte Übergangsfenster begrenzen. Den Herkunftsfall in Paket C prüfen, bevor weitergehende Garantien behauptet werden.

## 5. Restpaket C: Relay-Nachweis abschließen

**P2 als bereits vereinbartes Abnahmekriterium; verbleibender Teil von R6.**

Der neue Befehlsvergleich ist eine substanzielle Verbesserung. Er erfasst `Call(method, args)` in Reihenfolge; die Negativkontrollen erkennen vertauschte Methoden, geänderte Argumente und zusätzliche Befehle. Dieser Teil muss nicht erneut entworfen werden.

Die Abschlusssteuerung ist weiterhin teilweise zeitbasiert: [`settle()`, GainDiagnosticsTest.kt:582](https://github.com/kvmgithub/Spotilol/blob/2329f0f9a73b88db142d2fff2f29c333fa1f1bdb/base/api-spotify/src/test/kotlin/app/myspoti/spotify/GainDiagnosticsTest.kt#L582) wartet auf ein 300-ms-Fenster ohne neue Wiedergabebefehle. Das ist eine Ruheheuristik und kein allgemeiner Nachweis, dass alle späteren Timer abgearbeitet sind. Die angeforderte und gemeldete Pause liefert aber eine zusätzliche konkrete Abschlussbedingung. Es wurde hier kein dadurch übersehener später Befehl nachgewiesen; daraus entsteht kein weiterer eigenständiger Mergeblocker.

**Offen bleibt der Vergleich des tatsächlichen Relay-Pfads.** Die statische Prüfung spricht für unveränderte Antworten: `sent` ist ein unveränderlicher String, die Diagnose erhält ihn erst nach dem Aufruf von `answer()`, und die Auswertung liegt in `runCatching`. Präziser als die Übergabe: [`answer()`](https://github.com/kvmgithub/Spotilol/blob/2329f0f9a73b88db142d2fff2f29c333fa1f1bdb/base/host-android/src/main/kotlin/app/myspoti/host/android/ControlRelay.kt#L261) stellt die Antwort auf den Main-Thread; das bedeutet noch nicht, dass die Seite sie bereits erhalten hat.

Der Testauftrag für Claude lautet:

| Fall | Zu vergleichen |
| --- | --- |
| Gleiche erfolgreiche Track-Playback-Antwort, Diagnose aus/an | Gleicher Status, gleiche weitergereichte Header, bytegleicher Body, genau eine Antwort an dasselbe Ursprungsdokument |
| Diagnoseauswertung scheitert kontrolliert | Dieselbe Antwort bleibt erhalten; keine zweite Antwort, kein Zusatzrequest |
| Antwort mit vorhandenem AdBlock-Verhalten | Unverändertes Verhalten des bestehenden Filters bei aus/an; Diagnose sieht die tatsächlich weitergereichte Fassung |
| Reload und neue Aktivierung | Altes Ticket wird verworfen; Antwortweitergabe bleibt beim bisherigen Ursprungsdokument |
| Gepufferter Request aus alter Seite | Herkunft am tatsächlichen Relay-Eingang testen; entweder sicher ausschließen oder Diagnosebeobachtung der richtigen Seite zuordnen |

Ein kleiner Testzugang zur bestehenden Antwortweitergabe oder ein gezielter Android-Test ist dafür angemessen. Ein isoliert nachgebauter `sent`-Vergleich genügt nicht; der Test muss die Produktionsverdrahtung berühren. Keine umfassende Relay-Neuarchitektur und keinen zusätzlichen Spotify-Netzwerkpfad einführen.

**Der Test war bereits im ersten Review beauftragt.** Eine dafür notwendige, eng begrenzte Testnaht ist Bestandteil dieses Korrekturauftrags. „Kein weiterer Auftrag für eine Bestandsänderung“ ist deshalb kein Grund, diesen Teil erneut offen zu lassen.

## 6. Herkunft und Grenzen korrekt beschreiben

Diese Punkte sollen in derselben kleinen Korrekturrunde geklärt werden; sie begründen keinen zusätzlichen Architekturumbau.

### Geräteangabe bezeichnet den beim Antwortempfang gelesenen Cachezustand

[`onRelayAnswer`, GainDiagnostics.kt:177](https://github.com/kvmgithub/Spotilol/blob/2329f0f9a73b88db142d2fff2f29c333fa1f1bdb/base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt#L177) liest `deviceWord()` nach dem Parsen der Antwort. Das Ticket enthält lediglich Tag und Anfragefolge. Der Gerätestatus zum Anfragezeitpunkt ist darin nicht gespeichert.

Ausgeführter Ablauf: Ticket 1 bei `active=true` ziehen → `active=false` setzen → Antwort 2 empfangen → verspätete Antwort 1 empfangen. Anschließend tragen **beide** Kandidaten `dev=remote`, auch `ans=1`.

Das ist als „Host-Cachezustand beim Empfang“ interpretierbar. Es ist kein Nachweis, dass der ältere Kandidat für das Remote-Gerät angefragt oder von diesem verwendet wurde. Die Übergabe behauptet einen verspäteten Connect-Test; der [vorhandene Test ab Zeile 320](https://github.com/kvmgithub/Spotilol/blob/2329f0f9a73b88db142d2fff2f29c333fa1f1bdb/base/api-spotify/src/test/kotlin/app/myspoti/spotify/GainDiagnosticsTest.kt#L320) empfängt die erste Antwort jedoch schon vor dem Wechsel.

**Auftrag:** Den tatsächlich verspäteten Fall ergänzen und die Semantik eindeutig benennen. Ein ausdrücklich als Empfangssnapshot dokumentiertes Feld ist für M1 ausreichend. Soll zusätzlich der Anfragestatus erhalten bleiben, nur vorhandene Informationen im Ticket aufnehmen. Weder Variante beweist die Aktualität des Caches oder die serverseitige Geräteherkunft; keine neue Connect-Abfrage hinzufügen.

### `here=same` bedeutet gleiche Kontextart

Verglichen werden Kategorien wie `album` und `playlist`, nicht die vollständigen Kontext-IDs. Zwei verschiedene Playlists können daher `here=same` ergeben. Das ist für den Vergleich Album/Playlist verwendbar, muss vor M2 aber genau so beschrieben werden.

### Begrenzte Beobachtung bleibt begrenzte Beobachtung

Die Tabelle hält höchstens 256 Zuordnungen und vier Beobachtungen je Kandidat. Weitere Gegenproben zeigen erwartbare Grenzen: Mehrfachvorkommen derselben Datei innerhalb einer Antwort können die vier Plätze belegen; ein früh ausgeschöpftes 512-Dateilimit kann Kandidaten eines erst später aufgelisteten aktuellen Titels auslassen.

Diese synthetischen Grenzfälle sind **keine zusätzlichen Mergeblocker**. `capped`, `evicted` und `gain=none` dürfen lediglich nicht als Beweis für „Spotify lieferte keinen Gain“ interpretiert werden. Eine spätere Priorisierung des aktuellen Titels oder Zusammenfassung identischer Beobachtungen wäre eine gezielte Erweiterung, keine Voraussetzung für die vorliegenden Korrekturen.

## 7. Nächste Übergabe von Claude

Auf `work/gain-diagnostics` fortsetzen; tatsächlichen Branchstand vor Änderungen prüfen und die vorhandenen Dokumentcommits erhalten.

1. Paket A: asynchrone Suchabschlüsse und Fehlerzähler an die gültige Player-/Tag-Generation binden; beide Gegenproben als Regressionstests ergänzen.
2. Paket B: Herkunft der Seitenzeilen über Reload und Drosselung erhalten; den vorhandenen Lauf-/Tag-Schutz beibehalten.
3. Paket C: den fehlenden Relay-Vergleich am Produktionspfad umsetzen. Geräte-Semantik und den verspäteten Connect-Test aus Abschnitt 6 berichtigen.
4. Node- und Release-Notes-Prüfung sowie die bestehenden vollständigen CI-Tests auf dem neuen Code-Commit ausführen. Signierte Arbeitsbranch-APK, Prüfsumme und originale Testberichte bereitstellen.
5. Übergabe mit Befundstatus, konkreten Testfällen und verbleibenden Grenzen aktualisieren. Code-Commit und nachfolgende reine Dokumentcommits getrennt nennen.

Für diese Runde keine automatische Gain-Anwendung, keine zusätzlichen Spotify-Abfragen, keinen Umbau des Audiographs und keinen Beginn der nativen Migration vornehmen. Keine Änderung an Paketidentität, Signierung oder Veröffentlichungsablauf aus diesem Review ableiten.

### Gerätetest nach der Korrekturrunde

Die Tests müssen den tatsächlich installierten APK-Commit dokumentieren; die Versionsanzeige 1.4.0 allein unterscheidet die Arbeitsbranch-Builds nicht.

- Diagnose aus/an vergleichen: Titelwechsel, Lautstärke, Pause/Fortsetzen, Hintergrund, Sperrbildschirm und Rückkehr.
- Gleichen Titel aus Playlist und Album starten; Kontextart und Kandidatenherkunft prüfen.
- Reload und Ab-/Anmeldung getrennt prüfen. Seitenkennung und Laufkennung haben unterschiedliche Aufgaben. Nach einem vollständigen Prozessneustart können lokale Zähler wieder bei 1 beginnen.
- Nach Abschalten dürfen **keine neuen** Diagnosezeilen entstehen. Schon zuvor geschriebene Logcat-Zeilen müssen dadurch nicht aus dem Export verschwinden.
- Datenschutzprüfung auf die neuen `gain-diag`-Zeilen beziehen. Andere bestehende Logkanäle sind nicht durch diese Aussage abgedeckt.
- Fehlende private Playerfelder dürfen als `absent`, `invalid` oder `error` erscheinen; `pvSt=ok` ist nicht unter jeder Webplayer-Version erzwingbar. Entscheidend sind korrekte Kennzeichnung und unveränderte Funktion.

Android Auto und Offlinewiedergabe gehören nicht zu dieser Abnahme. Nach Code- und Nachweisprüfung folgt HKs Geräteabnahme; bis dahin bleibt M1 ohne Integrationsfreigabe.

