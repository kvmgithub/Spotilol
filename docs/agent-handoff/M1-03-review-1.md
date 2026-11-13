# MySpotilol – unabhängiges Review von M1

Stand: 13. November 2026. Gegenstand: Claudes Übergabe „MySpotilol-M1-Uebergabe-2026-11-13.md“ und die tatsächlich implementierte Gain-Diagnose.

**Urteil: noch nicht mergebereit.** Build und Artefakte sind nachvollziehbar; die vorhandenen Tests bestehen. Zusätzliche Gegenproben zeigen jedoch Fehler bei Fehlerisolation, Playerzuordnung, Lauftrennung und Datenherkunft. Die Aussage „offen ist nur der Gerätetest“ ist damit nicht bestätigt. Vor der M1-Abnahme sind die Befunde R1–R6 zu bearbeiten.

Die Grundarchitektur bleibt geeignet. Eine Neuentwicklung ist nicht erforderlich. Es wurden keine Repository-Quellen geändert, keine Commits erstellt und keine GitHub-Schreibaktionen ausgeführt. Untersuchungsprogramme und heruntergeladene Quellkopien liegen getrennt vom Projekt.

## 1. Geprüfter Stand und Nachweise

| Gegenstand | Ergebnis |
| --- | --- |
| Repository | [kvmgithub/Spotilol](https://github.com/kvmgithub/Spotilol) |
| Basis / Produktbranch | `64f97bb9ffe517b2f40ded45b7e934e23ec6a11a`, `myspoti-ui`, MySpotilol 1.4.0 |
| M1-Ergebnis | `82efcaa3eb1de21be85fc7dd8b6016a8eff260cd`, `work/gain-diagnostics` |
| Umfang | [Vier Commits, 14 Dateien, 1.352 hinzugefügte und neun entfernte Zeilen](https://github.com/kvmgithub/Spotilol/compare/64f97bb9ffe517b2f40ded45b7e934e23ec6a11a...82efcaa3eb1de21be85fc7dd8b6016a8eff260cd) |
| Branchkontrolle | Beide Branchspitzen entsprachen beim Abruf den oben genannten Commits. |
| CI | [Lauf 43355426911](https://github.com/kvmgithub/Spotilol/actions/runs/43355426911), `workflow_dispatch` auf dem Arbeitsbranch, Build erfolgreich. Veröffentlichung, Release-Notes-Aktualisierung und Branchbereinigung wurden übersprungen. |
| Original-Testberichte | Heruntergeladen und ausgewertet: 71 JUnit-Suiten mit **435 Tests, null Fehlern, null Fehlschlägen und null übersprungenen Tests**. Darin 20 `GainDiagnosticsTest`-Fälle. Zusätzlich zehn erfolgreiche Node-Tests. |
| Hier erneut ausgeführt | `node --test base/api-spotify/bridge-tests/gain-diagnostics.test.mjs`: 10/10 bestanden, Node 24.22.1. `python3 .github/scripts/test_ui_release_notes.py`: bestanden. |
| Zusätzliche JS-Prüfung | Tatsächliches `player-bridge.js` in der vorhandenen VM-Testumgebung ausgeführt; Playerwechsel, fehlender Player, entferntes Element, Kontextwechsel und werfende Diagnosefelder geprüft. |
| Zusätzliche Kotlin-Prüfung | Unveränderte Produktionsdateien `GainDiagnostics.kt` und `LogRedactor.kt` mit Kotlin 2.4.0 und kotlinx.serialization 1.11.0 kompiliert; fünf gezielte Gegenproben ausgeführt. Kein Nachbau ihrer Logik. |
| Grenzen | Kein kompletter lokaler Gradle-/Android-Build und kein eigener Gerätetest. Die vollständigen Build-/Unit-Test-Ergebnisse stammen aus dem überprüften CI-Lauf. Die Gegenproben sind kontrollierte Tests, keine Beobachtungen auf HKs Telefon. |

Die APK und die Testberichte waren hier tatsächlich herunterladbar. Die entsprechende Zugriffsbeschränkung in Claudes Übergabe gilt daher nicht für dieses Review.

### APK-Prüfung

Artefakt: [apk-82efcaa3eb1de21be85fc7dd8b6016a8eff260cd](https://github.com/kvmgithub/Spotilol/actions/runs/43355426911/artifacts/14005504241).

- Die selbst berechnete SHA-256 der enthaltenen APK lautet `489817ef6cc7b92b4182eeb783c454590f40202a6a134b6a797d9b73549e0f1b` und stimmt mit `SHA256SUMS.txt` und der Übergabe überein.
- `build-info.txt` nennt den geprüften Commit und CI-Lauf. Das in der APK enthaltene `bridge/player-bridge.js` ist **bytegleich mit der Datei dieses Commits**.
- Die CI-Signaturprüfung und `signers.txt` nennen genau den vorgesehenen Signierer mit SHA-256 `73cf309a74b1a107a1e1cbb9453806f7b0d8e803957ecd371cb51cfed0b21e9a`. Eine zweite lokale `apksigner`-Prüfung wurde nicht durchgeführt.
- Die Metadatendatei nennt `com.project.lol.fork.ui`, MySpotilol, Version 1.4.0 / Code 1004000 und keinen debuggable-Build. `REQUEST_INSTALL_PACKAGES` ist darin nicht enthalten.
- Die APK selbst enthält nur `arm64-v8a`-Bibliotheken. Der bestehende Scan auf die ausdrücklich entfernten Komponenten wurde an den heruntergeladenen DEX-Dateien wiederholt: keine Treffer. Das ist eine Prüfung dieser konkreten Ausschlussliste, kein allgemeiner Nachweis völliger Telemetriefreiheit.

## 2. Was bereits erfüllt ist

Die Diagnose ist ein eigener Baustein ohne eigene Netzwerk- oder Wiedergabebefehle. Der vorhandene Schalter wird beim Start und beim Umschalten bis zum Player weitergereicht. Normale `diag`-Nachrichten werden vor dem generischen Raw-Logging abgefangen. Gain `0.0`, fehlende Werte, JSON-null und ungültige Werte werden im Gain-Parser getrennt behandelt.

Die Begrenzung der gespeicherten Titel-/Datei-Zuordnungen und die zentrale Begrenzung der `gain-diag`-Zeilen sind implementiert. Die Tests führen den tatsächlichen JavaScript-Code aus. Der Relay-Diff verändert `sent` nicht und ruft die Antwortweitergabe vor der neuen Auswertung auf. Im Diff finden sich keine zusätzliche Spotify-Anfrage, Gain-Anwendung oder neue AudioContext-Erzeugung für M1.

Die CI-Erweiterung stellt die Arbeitsbranch-Artefakte bereit und schützt den bestehenden `ui-ci-logs`-Branch vor diesen Arbeitsbranchläufen. Paketname, Version und APK-Prüfungen bleiben erhalten.

## 3. Vor Abnahme zu korrigieren

Alle folgenden Punkte sind **P2: für die M1-Abnahme relevant**. Es wurde weder ein tatsächlicher Absturz auf dem Gerät noch ein Abfluss echter Zugangsdaten nachgewiesen. Die Priorisierung beruht auf reproduzierten Fehlverhalten und Lücken im geforderten Nachweis.

### R1 – Diagnosefehler unterbrechen die normale Statusweitergabe

**Fundstellen:** [player-bridge.js:1420](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/main/resources/bridge/player-bridge.js#L1420), [gdRead:278](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/main/resources/bridge/player-bridge.js#L278), [gdSend:308](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/main/resources/bridge/player-bridge.js#L308).

`pushState()` ruft die neue Diagnose vor dem regulären `state`-Post und dem nachfolgenden `boostRoute()` auf. Dieser Diagnoseaufruf ist nicht isoliert. Private Playerfelder und Diagnose-Leseoperationen dürfen jedoch fehlschlagen, ohne den bisherigen Hauptpfad zu verändern.

**Ausgeführte Gegenprobe:** Nach dem Einschalten wirft der Getter von `_playerVolume`; anschließend wird ein Titelwechsel A → B ausgelöst.

| Diagnose | Reguläre Statusmeldung für B | Ergebnis |
| --- | --- | --- |
| Aus | Gesendet | Kein Fehler |
| An | Nicht gesendet | `diagnostic field unavailable` entweicht aus dem Update-Aufruf |

Damit ist die Fehlerisolation des neuen Zweigs verletzt. Eine reale Wiedergabeunterbrechung wurde damit nicht behauptet; nachgewiesen ist der Ausfall der regulären Statusweitergabe im Fehlerfall. Die erste Diagnose liest außerdem private, veränderliche Webplayer-Strukturen: Diese Fehlerklasse gehört zur geforderten Robustheit.

**Korrekturziel:** Ausschließlich die neuen Diagnoseaufrufe absichern, einschließlich asynchroner Suche, Timer und eigener Schreibmarkierungen. Fehler dürfen reguläre Statusmeldungen, bestehendes Routing und Wiedergabebefehle nicht abbrechen. Fehlertexte dabei gemäß R5 begrenzen.

**Regressionstest:** Derselbe kontrolliert werfende Diagnosezugriff bei an/aus muss identische reguläre Nachrichten und bestehende Folgeaktionen ergeben. Danach muss eine weitere gültige Beobachtung wieder verarbeitet werden können.

### R2 – Der Sammler verfolgt nicht zuverlässig den aktuellen Player und sein Element

**Fundstellen:** [player-bridge.js:329](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/main/resources/bridge/player-bridge.js#L329), [gdState:336](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/main/resources/bridge/player-bridge.js#L336).

`gd.player` wird behalten. Bei einem Zustandsupdate untersucht `gdState` nur dessen `_player`; einen neuen Player sucht es nur bei einem Titelwechsel und fehlendem gespeicherten Player. Der Übergang zu keinem gültigen Medienelement ruft `gdFollow(null)` nicht auf.

**Drei ausgeführte Gegenproben:**

| Eingabe | Tatsächliche Ausgabe |
| --- | --- |
| Der SDK-Player wird durch B ersetzt; B hat `_playerVolume=0.3`, Elementlautstärke `0.25` und `_cubicVolume=false`. | Die Zeile trägt bereits Titel B, aber weiterhin A: `pv=0.8`, `vol=1`, `cubic=true`, `el=1`. Listener am alten Element: 1; am neuen: 0. |
| `_player` wird `null`. | Das alte Element behält seinen Listener und wird weiter als Messquelle ausgegeben. |
| Beim Einschalten fehlt der Player. Er erscheint später bei unverändertem Titel. | Auch nach einem Zustandsupdate und Lautstärkeänderung kein Listener; letzte Zeile bleibt `el=0`, `pvSt=absent`. |

Diese Fälle können die spätere Gain-Auswertung verfälschen, weil korrekte Titelkennungen mit falschen oder veralteten Pegelwerten verbunden werden.

**Korrekturziel:** Vorhandene Statusereignisse zur Aktualisierung der Playeridentität nutzen; auch fehlende und ungültige Elemente als Wechsel behandeln. Veraltete asynchrone Suchergebnisse dürfen nicht zurückbinden. Keine zusätzliche dauerhafte Pollingschleife und keine neuen Requests einführen. Die Integration mit der Lauf-/Seitengeneration aus R3 beachten.

**Regressionstests:** Alle drei Fälle; zusätzlich A → kein Element → B, verzögert aufgelöste alte Playersuche sowie wiederholtes Resume ohne doppelte Listener.

### R3 – Der Laufwechsel schützt Relay-Antworten, aber keine alten Seitenmeldungen

**Fundstellen:** [GainDiagnostics.kt:104](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt#L104), [onPage:134](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt#L134), [Bridge.kt:103](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/main/kotlin/app/myspoti/spotify/bridge/Bridge.kt#L103), [ControlRelay.kt:161](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/host-android/src/main/kotlin/app/myspoti/host/android/ControlRelay.kt#L161).

`newRun()` leert den Kotlin-Bestand. Die JS-Diagnosenachrichten besitzen aber weder Lauf- noch Seitengeneration. `onPage()` akzeptiert jede passende Nachricht, solange die Diagnose gerade eingeschaltet ist. Beim Laufwechsel werden die JS-Beobachtung und ihre wartenden Nachrichten nicht gleichzeitig ungültig gemacht.

**Ausgeführte Kotlin-Gegenprobe:** Eine Seitenmeldung wird vor `newRun()` erzeugt und danach übergeben. Ergebnis:

```text
gain-diag run=2 seq=1 ... ev=track track=t1 ... late=0
```

Die alte Beobachtung landet also im neuen Lauf. Als Kontrolle wurde eine Relay-Antwort mit altem Token tatsächlich verworfen; der Zähler steigt dort korrekt auf `late=1`. Der bestehende Logout-Test deckt nur diesen funktionierenden Relay-Fall ab.

Im Android-Pfad besteht zusätzlich ein Übergangsfenster: [SessionActions.kt:51](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/app/src/main/java/com/music/spotui/di/SessionActions.kt#L51) startet den neuen Lauf, bevor [WebPlayerHost.logout():583](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/host-android/src/main/kotlin/app/myspoti/host/android/WebPlayerHost.kt#L583) die asynchrone Bereinigung und Navigation abgeschlossen hat. Ein Request der alten Seite kann dann bereits das neue globale Token aufnehmen. Ein Token zum Anfragezeitpunkt allein beweist deshalb nicht die neue Sitzungszugehörigkeit.

**Korrekturziel:** Die Diagnose an Messlauf und Ursprungsseite binden. Bei Logout, Profilwechsel und Aus/Ein alte Beobachtungen sofort ungültig machen; auf einer neuen Seite nur die neue Generation zulassen. Das betrifft die Diagnosezuordnung, nicht die Weitergabe oder Blockierung bestehender Spotify-Requests.

**Regressionstests:** Gepufferte Seitenmeldung nach Logout; alte Meldung nach Aus/Ein; alte Playersuche nach neuem Lauf; alte Relay-Antwort; Request der alten Seite während des Übergangs. Reload/Resume müssen anschließend weiter funktionieren.

### R4 – Kontext und Herkunft der Kandidaten werden vermischt

**Fundstellen:** [GainDiagnostics.kt:165](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt#L165), [Kandidatenspeicherung:176](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt#L176), [Seitenzuordnung:212](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt#L212), [player-bridge.js:340](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/main/resources/bridge/player-bridge.js#L340).

**Drei ausgeführte Gegenproben:**

1. Derselbe Titel wechselt von einer Playlist in einen Albumkontext. `gd.ctx` wird nur bei geänderter Titel-URI aktualisiert. Eine folgende Lautstärkezeile meldet weiterhin `playlist` statt `album`.
2. Der durch `updated_state_ref` ausgewiesene aktuelle Antworttitel besitzt keinen Kontext. Ein nachfolgender Queue-Titel besitzt `album`. Die Antwortzeile meldet trotzdem `answerCurrent=t1 ctx=album`; `unknown` wurde durch den Kontext eines anderen Titels ersetzt.
3. Für dieselbe Titel-/Dateikombination wird zunächst ein Albumkandidat mit `gain=-4` erfasst. Danach kommt eine verspätete Playlist-Antwort, in der diese Datei mit `gain=-7` nur in der Queue vorkommt. Die nächste Album-Seitenzeile meldet `ctx=album gain=f1@10/mp4:candidate=-7.0000`, ohne die abweichende Quelle oder den Konflikt auszuweisen.

Fall 3 zeigt einen Fehler der Herkunftszuordnung bei solchen Eingaben. Er beweist ausdrücklich nicht, dass Spotify in der Praxis genau diese Gain-Unterschiede zwischen Album und Playlist liefert. M1 soll diese Frage erst untersuchbar machen und darf die dafür nötige Herkunft nicht verlieren.

`Offered.context` wird zwar gespeichert, aber bei der Ausgabe nicht genutzt. Ein später eingetroffener Wert überschreibt allein aufgrund gleicher URI und Datei den vorherigen. Die Kennzeichnung `candidate` ist richtig, löst jedoch dieses Herkunftsproblem nicht.

**Korrekturziel:** Seitenkontext und Antwort-/Kandidatenkontext getrennt kennzeichnen. Unbekannten Kontext nicht aus einem fremden Queue-Eintrag ergänzen. Herkunft und Beobachtungszeit beziehungsweise Anfragereihenfolge so erhalten, dass verspätete oder widersprüchliche Werte erkennbar bleiben. Anfragereihenfolge ist dabei ein Provenienzmerkmal, kein Beweis für serverseitige Aktualität. Bei unklarer Zuordnung einen alten Kandidaten nicht ohne Einschränkung als zum aktuellen Kontext gehörig darstellen.

**Regressionstests:** Die drei Fälle oben sowie eine verspätete Antwort mit derselben URI und Datei nach Connect-Wechsel. Vorhandene Geräteinformationen weiterverwenden; unbekannte oder alte Gerätezuordnung entsprechend kennzeichnen, ohne neue Geräteabfrage.

### R5 – Unbekannte Manifestnamen und Diagnosefehler umgehen die Ausgabe-Positivliste

**Fundstellen:** [GainDiagnostics.kt:169](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt#L169), [describe:300](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt#L300), [LIST:339](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt#L339), [Bridge.kt:224](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/main/kotlin/app/myspoti/spotify/bridge/Bridge.kt#L224), [SpotifyPlayer.kt:315](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/main/kotlin/app/myspoti/spotify/SpotifyPlayer.kt#L315).

**Pfad A, am Produktionscode inklusive Redactor ausgeführt:** Ein Manifestfeld `file_ids_synthetic_key_secret` wird akzeptiert. Im fertigen Log steht:

```text
entries=t1.f1@10/synthetic_key_secret:candidate=-3.0000
```

Das Muster begrenzt Zeichen und Länge, ist aber keine Positivliste bekannter Formate. Unbekannter Eingangstext wird in die Diagnose übernommen. Die vorhandenen Geheimnis-Tests platzieren ihre Marker in Werten, nicht in diesen übernommenen Feldnamen.

**Pfad B, am echten JavaScript ausgeführt:** Ein beim Einschalten werfender Getter mit einer markierten Roh-ID erzeugt:

```json
{"type":"result","id":1,"ok":false,"error":"spotify:track:RAWMARKER"}
```

Diese Nachricht durchläuft den generischen Resultatpfad. Die oben verlinkten Kotlin-Stellen schreiben den freien Fehlertext auch bei `quiet=true` ins Log. Der bestehende Redactor garantiert keine Entfernung solcher Track-URIs. Der frühe `type=diag`-Abzweig schützt diesen Fehlerpfad nicht.

**Korrekturziel:** Bekannte Manifestgruppen auf feste Bezeichner abbilden, unbekannte Gruppen auslassen oder als festen unbekannten Typ zählen. Diagnosefehler bereits im neuen Zweig in feste, begrenzte Fehlercodes umwandeln; keine freien Ausnahmeinhalte über Resultat, Log oder unhandled rejection weitergeben. Die allgemeine Fehlerbehandlung anderer Bridgefunktionen nicht pauschal umbauen. Fehlermeldungen ebenfalls begrenzen.

**Regressionstests:** Marker in Manifestnamen, geworfenen Fehlern und unerwarteten Diagnosefeldern. Den tatsächlichen Weg bis zur Logausgabe prüfen; eine isolierte Prüfung normaler `diag`-Nachrichten reicht nicht. Reale Zugangsdaten wurden in diesen Tests nicht verwendet.

### R6 – Der Gleichheitstest belegt weder Befehlsargumente noch Reihenfolge

**Fundstellen:** [GainDiagnosticsTest.kt:400](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/test/kotlin/app/myspoti/spotify/GainDiagnosticsTest.kt#L400), [Vergleich:445](https://github.com/kvmgithub/Spotilol/blob/82efcaa3eb1de21be85fc7dd8b6016a8eff260cd/base/api-spotify/src/test/kotlin/app/myspoti/spotify/GainDiagnosticsTest.kt#L445).

`RecordingHost` speichert nur Methodennamen. Der Vergleich sortiert diese Namen. Damit bleiben geänderte Lautstärken, andere Ziel-URIs und vertauschte Befehle unsichtbar, solange die Namen und Anzahlen gleich sind. Zusätzlich hängt das Aufzeichnungsende von `Dispatchers.Default` und einer Sekunde Nachlauf ab. Die Änderung in `82efcaa` reduziert einen konkreten Zeitfehler, macht den Test aber noch nicht zu einem deterministischen Sequenznachweis.

Die normalen Tests sind nützlich. Ihr Ergebnis trägt lediglich eine engere Aussage als die behauptete Gleichheit aller Wiedergabebefehle. R1 zeigt zudem einen Fehlerpfad, den sie nicht abdecken.

**Korrekturziel:** Relevante Aufrufe als geordnete Folge aus Methode und strukturierten Argumenten erfassen. Auf definierte Abschlussbedingungen oder kontrollierte Zeit warten. Nur ausdrücklich reine Beobachtungsaufrufe herausnehmen; zusätzliche wirksame Aufrufe und geänderte Argumente müssen auffallen. Dazu die Relay-Ausgabe bei Diagnose an/aus mit denselben Eingaben prüfen, einschließlich Fehler in der Diagnose.

**Nachweis:** Der Test muss absichtlich vertauschte Wiedergabebefehle und absichtlich veränderte Argumente erkennen. Die Produktionsimplementierung dafür nicht ändern. Wiederholtes grünes Ausführen allein ersetzt diese Prüfung der Testempfindlichkeit nicht.

## 4. Nachrangige Punkte und ausgewiesene Grenzen

| Punkt | Bewertung und Behandlung |
| --- | --- |
| Stabile Mess-IDs | Zusätzliche Kotlin-Gegenprobe: A wird als `t1.f1` erfasst; 256 weitere Seiten-URIs verdrängen nur die Titel-Alias-Tabelle. Danach erscheint A als `t258` mit demselben `f1`, obwohl `table=1 evicted=0` geblieben ist. Kein Überschreiten der 256 gespeicherten Zuordnungen, aber ein unerklärter Bruch der Korrelation. Im Zusammenhang mit R4 Aliase an gehaltene Zuordnungen koppeln oder Aliasverdrängungen explizit ausweisen; Speichergrenze erhalten. |
| Bedeutung von `loudSt=none` | Der bestehende Lautheitscache fasst fehlende, ungültige und explizit leere Antworten als NaN zusammen. Daraus folgt nicht eindeutig „Spotify hat keinen Wert“. Mindestens Formatdokumentation und Bezeichnung auf „kein verwertbarer Cachewert; Ursache unbekannt“ korrigieren. Kein neuer Request und keine Änderung der Normalisierungsentscheidung. Falls die ursprüngliche Statusunterscheidung auch für Loudness vollständig erfüllt werden soll, diese Information passiv an der ohnehin vorhandenen Antwort erfassen. |
| Länge von Seitenzeilen | Antwortzeilen begrenzen sichtbare Kandidaten auf zwölf; Seitenzeilen können alle 256 ausgeben. Für den Logcat-basierten Export einen Maximalfall prüfen und auch dort eine begrenzte Kandidatenliste mit Restzähler verwenden. Eine tatsächliche Abschneidung auf HKs Gerät wurde nicht getestet. |
| Arbeit unter der gemeinsamen Sperre | Die JSON-Parsephase liegt außerhalb der Sperre; das Durchlaufen aller Titel und Kandidaten liegt darin. 256 begrenzt den Endbestand, nicht diese Verarbeitung. Eine große gültige Antwort gegen eine gleichzeitig eintreffende Seitenmeldung prüfen. Keine unbelegte Behauptung eines gemessenen UI-Hängers. |
| Einstellungstext | Der Schaltertext beschreibt weiterhin nur das Einblenden des Logeintrags. Eine knappe Ergänzung zur aktivierten Pegeldiagnose ist eine passende M1-Dokumentationskorrektur; dafür braucht es keine neue UI-Konzeption. |
| Gerätetests | Diagnose aus/an, Titelwechsel, Pause/Fortsetzen, Hintergrund, Sperrbildschirm, Rückkehr, Abschalten und Export bleiben offen. Für die Korrekturen außerdem Reload und Sitzungstrennung gezielt prüfen. Keine Android-Auto- oder Offline-Testpflicht. |

## 5. Akzeptierte Abweichungen und bewusst ausgeklammerte Arbeiten

- **Kein `selected`:** sachlich richtig. Die vorhandenen Quellen belegen keine eindeutig tatsächlich verwendete Datei. `candidate` und `unknown` sind die richtigen Aussagen.
- **Dokumente außerhalb des Repositorys:** für das Review akzeptiert. Die Übergabe liegt als Datei vor; der im Auftrag genannte Ablageort ist kein technischer Mergeblocker. Die darin genannte zusätzliche Nutzerregel wurde hier nicht unabhängig rekonstruiert.
- **Gradle nur in CI:** akzeptiert. Commit, Joblog, heruntergeladene Testberichte und APK sind überprüfbar. Ein separater gefilterter Lauf der 20 Diagnosetests ist nicht zusätzlich notwendig, wenn der vollständige Lauf diese tatsächlich ausführt.
- **Begrenzte Relay-Abdeckung:** kein Ausbau zu Dealer-Nachrichten, `fetch(Request)` oder neuen Netzwerkpfaden in dieser Korrekturrunde.
- **Ad-Guard und bestehender Audiograph:** keine verdeckte Reparatur des vorhandenen `boost.routed`-Verhaltens, keine neue AudioContext-Erzeugung und keine Umgestaltung der Werbestummschaltung. Unmarkierte Guard-Schreibungen und fehlende Graph-Zwischenwerte bleiben als Beobachtungslücken dokumentiert.
- **Geräteinformationen aus vorhandenen Daten:** keine zusätzliche Connect-Abfrage. Die Frische muss bei der Interpretation berücksichtigt werden; der vorhandene Cache ist keine Garantie des gerade aktiven Geräts.
- **Gain-Wirkung und native Wiedergabe:** keine automatische Anwendung von `gain_db`, keine Behauptung über Spotify-Normalisierung, kein Beginn von M2 oder M4 aus diesem Review heraus.
- **Allgemeine Altprobleme:** bestehende Logpfade anderer Nachrichtentypen und allgemeine Player-Refactorings sind nicht Gegenstand dieses M1-Diffs. R5 behandelt die neu eingeführten Ausgabepfade.

## 6. Konkreter Korrekturauftrag an Claude

**M1 auf `work/gain-diagnostics` weiterbearbeiten.** Ausgangspunkt dieses Reviews ist `82efcaa3eb1de21be85fc7dd8b6016a8eff260cd`. Vor Änderungen den tatsächlichen Branchstand prüfen und zwischenzeitliche Arbeit erhalten.

1. R1 und R5 absichern: ausschließlich der Diagnosezweig darf bei einem Diagnosefehler scheitern; es dürfen keine freien Diagnosefehlertexte oder unbekannten Manifestnamen ausgegeben werden.
2. R2 und R3 gemeinsam korrigieren: Player, Element, Messlauf und Ursprungsseite müssen zusammenpassen; alte asynchrone Ergebnisse und Meldungen verwerfen, ohne den bestehenden Netzwerk-/Wiedergabepfad zu verändern.
3. R4 korrigieren: Kontext und Kandidatenherkunft erhalten, Konflikte beziehungsweise unbekannte Zuordnung sichtbar machen. Den Aliasfall aus Abschnitt 4 dabei berücksichtigen.
4. R6 und die zugehörigen Regressionstests ergänzen. Loudness-Dokumentation und Schaltertext berichtigen; Last- und Zeilengrenzen prüfen.
5. Die bestehenden Node-, Python- und vollständigen CI-Prüfungen erneut auf dem neuen Ergebniscommit ausführen. Signierte Arbeitsbranch-APK, Prüfsumme und Testberichte bereitstellen. Produktbranch, Signierung und reguläre Veröffentlichung unverändert lassen.

Die nächste Übergabe enthält pro Befund **„behoben“, „mit Beleg widerlegt“ oder „offen“**, die konkrete Testzuordnung, Basis-/Ergebniscommit, CI-Link und APK-Prüfsumme. Bei einer abweichenden technischen Lösung muss deren Verhalten die genannten Gegenproben erfüllen; die vorgeschlagenen Implementierungsdetails sind keine Pflichtarchitektur.

ChatGPT/Codex prüft anschließend wieder den tatsächlichen Diff und die Nachweise. HKs Gerätetest bleibt ein eigener Abnahmeschritt. Bis zur Nachprüfung dieser Korrekturen ist M1 nicht zur Integration freigegeben.
