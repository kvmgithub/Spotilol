# MySpotilol – M1, unabhängiges Review 3

Stand: 13. November 2026. Grundlage sind Claudes dritte Übergabe, der tatsächliche Korrekturdiff, eigene Gegenproben, die heruntergeladenen CI-Testberichte und die signierte APK.

**Urteil: Die konkreten Restfälle aus den Paketen A, B und C sind behoben beziehungsweise nachgewiesen. Eine neue P2-Regression verhindert noch den vollständigen Abschluss von M1: Die Ausgabe-Warteschlange ist über Seitenwechsel hinweg nicht mehr begrenzt.**

Die Korrektur bleibt klein. Playersuche, Relay-Testnaht und Diagnosearchitektur müssen nicht erneut entworfen werden. Die neue Beanstandung betrifft eine ausdrücklich vereinbarte Mengenbegrenzung aus dem ursprünglichen M1-Auftrag. Ein Absturz, hörbarer Wiedergabefehler oder tatsächlicher Speichermangel auf dem Telefon wurde hier nicht nachgewiesen.

Codex hat ausschließlich gelesen und Prüfungen außerhalb der Repository-Quellen ausgeführt. Kein Code wurde geändert, kein Branch integriert und kein Release oder GitHub-Kommentar veröffentlicht. Claude bleibt Implementierer; HKs Gerätetest und Integrationsentscheidung sind gesonderte Schritte.

## 1. Offener Befund R7: Unbegrenzter Ausgabepuffer bei wiederholten Seitenwechseln

**Priorität P2. Vor dem Abschluss von M1 korrigieren.**

Fundstellen im geprüften Commit:

- [`GainDiagnostics.kt`, Zeile 139: `waiting`](https://github.com/kvmgithub/Spotilol/blob/5071fce001759dc6e712cf2a74d8e60fd5b1940e/base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt#L139)
- [`GainDiagnostics.kt`, Zeile 339: `emit`](https://github.com/kvmgithub/Spotilol/blob/5071fce001759dc6e712cf2a74d8e60fd5b1940e/base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt#L339)
- [`GainDiagnostics.kt`, Zeile 352: `flushReady`](https://github.com/kvmgithub/Spotilol/blob/5071fce001759dc6e712cf2a74d8e60fd5b1940e/base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt#L352)

### Ursache und Auswirkung

Vorher wurde je Ausgabeart nur eine wartende Zeile gehalten. Jetzt enthalten die Schlüssel zusätzlich die Seite: `page:<Tag>` und `answer:<Tag>`. `armPage()` vergibt fortlaufend neue Tags; `pageGone()` entfernt die bereits akzeptierten Ausgaben nicht. Die Map besitzt keine globale Größenbegrenzung.

Die Trennung korrigiert die Seitenherkunft. Sie ermöglicht aber zugleich, dass jede neue Seite weitere wartende Schlüssel hinzufügt. Die Grenze von zwei **ausgegebenen** Zeilen pro Sekunde begrenzt nicht die Zahl der **gespeicherten** Zeilen. Kommen neue Seitenmeldungen und Antworten schneller hinzu, als die Ausgabe sie abarbeitet, wachsen Speicherbedarf und Verzögerung mit der Zahl der Seitenwechsel. Aktuelle Messungen warten dann hinter alten Seiten.

Der Kommentar, Seiten kämen ohnehin mit Sekundenabstand, ersetzt keine durchgesetzte Grenze. Der ursprüngliche Auftrag verlangt unter Abschnitt 5.C ausdrücklich: **„Keine unbeschränkte Warteschlange.“** Das ist daher keine nachträgliche Erweiterung des Umfangs.

### Unabhängig ausgeführte Gegenprobe

Dasselbe separate Kotlin-Prüfprogramm wurde gegen das unveränderte Produktions-`GainDiagnostics.kt` von `2329f0f` und `5071fce` kompiliert. Der Scheduler arbeitet mit einer virtuellen Uhr; er führt fällige Flush-Aufgaben tatsächlich aus.

Ablauf:

1. Diagnose einschalten und eine Seite aktivieren.
2. Eine gültige Seitenmeldung und eine gültige Track-Playback-Antwort für deren Ticket zuführen.
3. Die Uhr um 500 ms vorrücken.
4. `pageGone()` und `armPage()` ausführen; Schritte 2–3 wiederholen.
5. Wartende Zeilen zählen und anschließend ohne neue Ereignisse bis zum leeren Puffer weiterlaufen lassen.

| Simulierte Seiten | Simulierte Laufzeit | Wartende Zeilen vorher, `2329f0f` | Wartende Zeilen jetzt, `5071fce` | Zusätzliche Abbauzeit jetzt |
| --- | ---: | ---: | ---: | ---: |
| 10 | 5 s | 0 | 9 | 5 s |
| 100 | 50 s | 0 | 99 | 50 s |
| 1.000 | 500 s | 0 | 999 | 500 s |

Die Messpunkte liegen jeweils nach dem letzten 500-ms-Schritt. In beiden Versionen blieb die tatsächliche Ausgabe bei höchstens zwei Zeilen je gleitendem 1.000-ms-Fenster. Ausschalten leerte auch im neuen Stand den Puffer und verhinderte weitere Ausgabe.

**Einordnung:** Das ist ein synthetischer Lasttest der Diagnose und kein auf dem Pixel beobachteter Reload-Zyklus. Er belegt die fehlende Obergrenze und die wachsende Verzögerung, keinen gemessenen OOM oder Wiedergabeausfall. Die vorherige Begrenzung funktionierte unter derselben Eingabefolge.

### Eng begrenzter Korrekturauftrag an Claude

- Eine feste, kleine Obergrenze für **alle** wartenden Diagnosezeilen einführen. Die Zahl gleichzeitig auftretender Seiten darf sie nicht erhöhen.
- Bei Überlauf nach einer dokumentierten Regel ältere wartende Beobachtungen verwerfen oder zusammenfassen. Die Zahl verworfener beziehungsweise zusammengefasster Ereignisse muss nachvollziehbar bleiben.
- Die beim Erfassen festgehaltene Seitenkennung erhalten. Eine alte Zeile darf niemals mit dem Tag der neuen Seite erscheinen. Unterschiedliche Seiten nicht als eine gemeinsame Beobachtung ausgeben.
- Die globale Grenze von zwei Zeilen pro Sekunde und das vollständige Leeren bei Aus/Ein beziehungsweise Laufwechsel beibehalten. Keine zusätzlichen Netzwerkzugriffe oder Wiedergabebefehle einführen.
- Einen Regressionstest über viele Seitenwechsel mit laufender virtueller Uhr ergänzen. Prüfen: feste Pufferobergrenze, nachvollziehbarer Verlustzähler, korrekte Herkunft verbleibender Zeilen, unveränderte Ausgaberate und zuverlässiges Abschalten.

Eine begrenzte Warteschlange mit definierter Verdrängung genügt. Kein Umbau der Kandidatentabelle, des Players oder des Relays ist dafür erforderlich. Falls alte Seitenzeilen künftig vollständig verworfen werden, muss der Test die dokumentierte Verwerfungsregel prüfen; das war bereits in Review 2 als zulässige Lösung vorgesehen.

## 2. Abgeschlossene Punkte aus Review 2

| Paket | Ergebnis | Nachweis |
| --- | --- | --- |
| **A – Playersuche** | **Behoben.** Erfolgs- und Ablehnungspfad prüfen Aktivierung, Generation und die tatsächlich aktuelle SDK-Promise. | Eigene Gegenproben aus Review 2 erneut ausgeführt; neue Regressionstests zusätzlich gegen den vorherigen Code geprüft. |
| **B – Seitenherkunft** | **Behoben für die beanstandete Zuordnung.** Gepufferte Zeilen und Kandidaten tragen die ursprüngliche `page`-Kennung. | Eigene Kotlin-Gegenprobe: `1:track:0.8000`, `1:volume:0.5000`, `2:track:0.9000`, jeweils `pl=1/el=1`. Die neue Mengenregression steht separat unter R7. |
| **C – Relay-Vergleich** | **Für den vereinbarten automatisierten Nachweis erfüllt.** Die Tests verwenden den produktiv eingebundenen letzten Antwortschritt und den echten Fetch-Wrapper. | Sechs `RelayAnswersTest`-Fälle in den originalen CI-Berichten; Produktionsverdrahtung gelesen; Fetch-Wrapper-Test lokal bestanden. |
| **Geräte-Semantik** | **Korrigiert und getestet.** `rxDevice` beziehungsweise `rxDev` bezeichnet den beim Empfang gelesenen Host-Cache. | Tatsächlich verspätete Antwort nach Gerätewechsel getestet; eigene Gegenprobe bestätigt `ans=2:remote` und anschließend `ans=1:remote`. |
| **Interpretationsgrenzen** | **Ausreichend dokumentiert.** `here=same` meint dieselbe Kontextart. `gain=none`, `capped` und `evicted` beweisen keine Abwesenheit eines Spotify-Werts. | KDoc und Übergabe überprüft. Keine zusätzliche Connect-Abfrage eingeführt. |

### Playersuche: Die vorherigen Reproduktionen sind geschlossen

In der Gegenprobe ersetzt das SDK die noch offene Suche A durch B, ohne dazwischen ein Statusereignis zu schicken. Sobald A antwortet, wird B ermittelt: `pv=0.3`, `vol=0.25`, kein Listener an A und genau einer an B. Das nächste Statusereignis verändert diese Bindung nicht.

Eine Ablehnung der alten Suche nach Aus/Ein verändert den Fehlerzähler des neuen Tags nicht mehr. Die Ablehnung einer aktuellen Suche bleibt als fester Fehlercode erkennbar. Der markierte freie Fehlertext erscheint nicht in den erfassten Nachrichten.

Die beiden neuen Regressionstests scheitern gezielt gegen `2329f0f` und bestehen gegen `5071fce`. Sie prüfen damit die zuvor vorhandenen Fehler und bestehen nicht lediglich unabhängig von der Korrektur.

Fundstelle: [`player-bridge.js`, `gdLocate`](https://github.com/kvmgithub/Spotilol/blob/5071fce001759dc6e712cf2a74d8e60fd5b1940e/base/api-spotify/src/main/resources/bridge/player-bridge.js#L365).

### Relay: Testnaht ist Teil des Produktionspfads

[`ControlRelay`](https://github.com/kvmgithub/Spotilol/blob/5071fce001759dc6e712cf2a74d8e60fd5b1940e/base/host-android/src/main/kotlin/app/myspoti/host/android/ControlRelay.kt#L204) ruft die neue Klasse [`RelayAnswers`](https://github.com/kvmgithub/Spotilol/blob/5071fce001759dc6e712cf2a74d8e60fd5b1940e/base/host-android/src/main/kotlin/app/myspoti/host/android/RelayAnswers.kt#L37) auf. Es handelt sich nicht um eine bloße zweite Implementierung für Tests.

Die Testfälle vergleichen Status, weitergereichte Header, Body und Antwortanzahl bei Diagnose aus/an. Sie decken werfende Ticket-/Diagnosefunktionen, den vorhandenen AdBlock-Filter, alte Tickets nach Reload und verspätet eintreffende Requests einer alten Seite ab. Die Diagnose erhält die tatsächlich weitergereichte Textfassung. Sie kann deren unveränderlichen String nicht nachträglich umschreiben. Ihre Ausnahmen bleiben im Diagnosezweig.

Der Fetch-Wrapper liest den Seiten-Tag je Request und übergibt ihn im internen Bridge-Paket. Der native Requestbau verwendet dieses Feld nicht als HTTP-Header oder Body-Bestandteil. Der lokal ausgeführte Test extrahiert das Script unmittelbar aus `ControlRelay.kt`; auch ungültige Tags und ein werfender Getter verhindern darin die Anfrage nicht.

Die Verschiebung des Antwortcodes erhält die bisherige Set-Cookie-Filterung sowie AdBlock- und `adAhead`-Behandlung. Die Antwortfunktion bleibt an den ursprünglichen `JavaScriptReplyProxy` gebunden.

**Nachweisgrenze:** Die JVM-Tests prüfen den produktiven Antwortschritt mit einem Test-Callback. Sie führen keinen echten Android-WebMessage-Kanal aus. In der App wird die Antwort vor der Diagnoseauswertung auf den Main-Thread gestellt; daraus folgt nicht, dass sie vor der Auswertung bereits im Dokument angekommen ist. Diese Grenze ist in der Übergabe korrekt benannt und begründet keinen weiteren offenen Codeauftrag.

## 3. Geprüfter Code- und Buildstand

| Gegenstand | Ergebnis |
| --- | --- |
| Repository | [kvmgithub/Spotilol](https://github.com/kvmgithub/Spotilol) |
| Vorheriger Code | `2329f0f9a73b88db142d2fff2f29c333fa1f1bdb` |
| **Geprüfter neuer Code** | **`5071fce001759dc6e712cf2a74d8e60fd5b1940e`** |
| Codeumfang | Ein neuer Codecommit, sieben geänderte oder neue Code-/Testdateien. [Vergleich](https://github.com/kvmgithub/Spotilol/compare/2329f0f9a73b88db142d2fff2f29c333fa1f1bdb...5071fce001759dc6e712cf2a74d8e60fd5b1940e) enthält zusätzlich die zwei bereits vorhandenen Dokumentcommits. |
| Arbeitsbranch-Spitze | `4238b13e1fac4944d751a2389445e1886aabd126`. Die zwei Commits nach `5071fce` ändern ausschließlich `M1-05-review-2.md` und `M1-06-uebergabe-3.md` unter `docs/agent-handoff/`. |
| Produktbranch `myspoti-ui` | Unverändert bei `63bff9587c7f3de2a258cd8b8278b0c6e64d9994`. M1 ist nicht integriert. |
| Quellintegrität | Alle sieben Code-/Testdateien stimmen mit den Git-Blob-Hashes überein. Abschließender Archivvergleich: 635 reguläre Quelldateien unverändert, keine zusätzlichen Dateien im Snapshot. |
| CI | [Lauf 43427715418](https://github.com/kvmgithub/Spotilol/actions/runs/43427715418), Head `5071fce`, Build erfolgreich. Veröffentlichung und Produktbranch-Bereinigung übersprungen. |
| JUnit | Originale Berichte ausgewertet: **454 Tests in 72 Suiten; null Fehler, null Fehlschläge, null übersprungene Tests**. Enthalten: 33 `GainDiagnosticsTest`, sechs `RelayAnswersTest`. |
| JavaScript | **23/23** in CI und hier lokal mit Node bestanden. |
| Weitere Prüfungen | Release-Notes-Prüfung lokal bestanden; separate JavaScript- und Kotlin-Gegenproben wie oben beschrieben. |
| Grenzen | Kein eigener Android-Gerätetest und kein vollständiger lokaler Gradle-/APK-Build. Die vollständigen Android-/Kotlin-Testergebnisse stammen aus dem überprüften CI-Lauf. |

Die 300-ms-Ruheheuristik des bestehenden Befehlsvergleichs wird durch dieses Review nicht neu beanstandet. Die bereits akzeptierten geordneten Methoden-/Argumentvergleiche und Negativkontrollen bleiben gültig. Ebenso entstehen aus den zuvor dokumentierten Kandidaten- und Antwortlimits keine zusätzlichen Arbeitsaufträge.

### APK-Nachweis

[APK-Artefakt](https://github.com/kvmgithub/Spotilol/actions/runs/43427715418/artifacts/14037574091) und [Testberichte](https://github.com/kvmgithub/Spotilol/actions/runs/43427715418/artifacts/14037624051) wurden in dieser Reviewsitzung tatsächlich heruntergeladen.

| Prüfung | Ergebnis |
| --- | --- |
| Datei | `MySpotilol-arm64-1.4.0.apk` |
| Selbst berechnete APK-SHA-256 | `0cf4bc08c3e76f7f027882db19ceffc09eff0a21b48a0979022034e8dd29b97a` |
| Zuordnung | Prüfsumme stimmt mit Übergabe und `SHA256SUMS.txt` überein; `build-info.txt` nennt Commit `5071fce` und Lauf `43427715418`. Beide heruntergeladenen ZIP-Dateien stimmen zusätzlich mit den von GitHub gemeldeten Artefakt-Digests überein. |
| Eingebettete Bridge | `bridge/player-bridge.js` ist bytegleich mit der geprüften Quelldatei. |
| Signierer | CI-Prüfung und `signers.txt`: genau ein Signierer, SHA-256 `73cf309a74b1a107a1e1cbb9453806f7b0d8e803957ecd371cb51cfed0b21e9a`. Keine zusätzliche lokale `apksigner`-Prüfung. |
| Metadaten | Paket `com.project.lol.fork.ui`, Version 1.4.0 / Code 1004000, laut geprüften Metadaten nicht debuggable und ohne Installer-Berechtigung. |
| Inhalt | Nur `arm64-v8a`-Bibliotheken. Wiederholter DEX-Ausschlussscan ohne Treffer für die acht konkret ausgeschlossenen Komponenten. Das ist kein allgemeiner Nachweis der Telemetriefreiheit. |

Die Versionsanzeige 1.4.0 unterscheidet diese Arbeitsbranch-APKs nicht. Für Testprotokolle gelten Commit und SHA-256 der verwendeten APK.

## 4. Kleine Berichtigung der Übergabe und des Gerätetestplans

Zwei Aussagen zur Lebensdauer der Kennungen müssen präziser werden:

- **Seitenreload im selben Prozess:** neue `page`-Kennung; bei unverändertem Messlauf derselbe `run`. Player- und Elementnummern dürfen erneut bei 1 beginnen. Allein das Verschieben der App in den Hintergrund garantiert keinen Reload.
- **Tatsächlicher Prozessneustart:** `run` und `page` können beide wieder bei 1 beginnen. Eine Startzeile markiert den Beginn eines neuen Abschnitts, ist aber keine global eindeutige Prozesskennung. Getrennte Aufzeichnungen beziehungsweise deren Zeit- und Startgrenzen erhalten; keine prozessübergreifende Eindeutigkeit behaupten.
- **Logout oder Sitzungswechsel im laufenden Prozess:** neuer Messlauf; bisherige Kandidaten und wartende Ausgaben werden verworfen. Das ist getrennt vom einfachen Reload zu testen.

Das ist eine Dokumentkorrektur. Für M1 wird keine persistente Prozess-ID verlangt.

Der spätere Gerätetest bleibt auf die vereinbarten Funktionen beschränkt: Diagnose aus/an, Titel- und Kontextwechsel, Lautstärke, Pause/Fortsetzen, Hintergrund/Sperrbildschirm, echter Reload sowie Ab-/Anmeldung. Android Auto und Offlinewiedergabe gehören nicht zu HKs Testumfang. Eine weitergehende automatische Gain-Korrektur lässt sich aus diesen Diagnoseproben noch nicht ableiten.

## 5. Nächste Übergabe von Claude

Auf `work/gain-diagnostics` weiterarbeiten und die vorhandenen Dokumentcommits erhalten. Auftrag dieser Runde ist **R7 samt Regressionstest und die Dokumentberichtigung aus Abschnitt 4**.

1. Den global begrenzten Ausgabepuffer mit nachvollziehbarer Verdrängung implementieren. Keine Erweiterung des Wiedergabepfads.
2. Den Mehrseiten-Lasttest aus Abschnitt 1 ergänzen. Bestehende Herkunfts-, Laufwechsel-, An/Aus-, Playersuche- und Relay-Vergleiche müssen weiter bestehen.
3. Node- und Release-Notes-Prüfung sowie die vorhandenen vollständigen CI-Prüfungen auf dem neuen Codecommit ausführen.
4. Neue signierte Arbeitsbranch-APK, SHA-256, Testberichte, exakte Commit-ID und die geänderte Pufferregel übergeben. Kein Merge und kein regulärer Release im Rahmen dieses Auftrags.

Codex prüft anschließend die Begrenzung und den betroffenen Diff. A, C und die bereits geschlossenen Teile von B und R4 werden nur bei relevanten Änderungen oder neuen konkreten Fehlernachweisen erneut geöffnet. M2 beginnt erst nach Abschluss von M1 und Auswertung realer Messungen.
