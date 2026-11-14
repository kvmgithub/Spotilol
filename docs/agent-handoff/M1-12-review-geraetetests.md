# MySpotilol – Review der beiden M1-Gerätetests

Stand: 13. November 2026. Grundlage sind Claudes zwei Gerätetest-Auswertungen und der erneut abgeglichene Repository-Code.

**Urteil: Die technische Freigabe aus Review 4 bleibt bestehen. Die beschriebenen Gerätetests liefern zusätzliche brauchbare Beobachtungen; einen neuen M1-Codefehler belegen sie nicht. Die vollständige Geräteabnahme ist damit aber nicht nachgewiesen. Insbesondere fehlt der Reload bei eingeschalteter Diagnose. Dafür genügt ein gezielter kurzer Test; ein kompletter dritter Durchgang ist unnötig.**

Eine wesentliche Aussage beider Auswertungen ist falsch: **Der geprüfte Build bietet bereits Einstellungen → App → „Player neu starten“.** Auf einen zufälligen Wiedergabehänger oder eine Werbung muss für den Reload-Test nicht gewartet werden.

Codex hat ausschließlich lesend geprüft. Kein Repository-Code wurde verändert, kein Merge und keine Veröffentlichung durchgeführt. Claude bleibt Implementierer; HK entscheidet über Integration und regulären Release.

## 1. Prüfgrundlage und Beleggrenzen

Gelesen wurden vollständig:

- `MySpotilol-M1-Geraetetest-1-Auswertung-2026-11-13.md`
- `MySpotilol-M1-Geraetetest-2-Auswertung-2026-11-13.md`

**Die darin ausgewerteten ursprünglichen App- und Systemlogs liegen diesem Review nicht vor.** Zeitabläufe, Zeilenzahlen, Datenschutzprüfungen und Gerätebeobachtungen werden deshalb als Angaben dieser Auswertungen behandelt. Codex hat sie gegen den Code und die bisherigen Prüfergebnisse bewertet, die Rohlogs aber nicht unabhängig erneut ausgewertet.

| Gegenstand | Erneut festgestellter Stand |
| --- | --- |
| Geprüfter M1-Code | `18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac` |
| Arbeitsbranch `work/gain-diagnostics` | `59d18a1bd384bee1d547fb30263d9a2b04ec3062` |
| Änderungen nach dem Codecommit | Vier Commits, ausschließlich fünf Dokumente unter `docs/agent-handoff/`; keine Code- oder Teständerung. |
| Produktbranch `myspoti-ui` | Weiterhin `63bff9587c7f3de2a258cd8b8278b0c6e64d9994`; M1 nicht integriert. |
| Automatisierte Prüfungen | Review 4 bestätigte 455 JUnit- und 23 JavaScript-Tests. Die vorhandene JUnit-Auswertung wurde erneut gelesen; wegen unverändertem Code kein vollständiger neuer Testlauf. |
| Test-APK | SHA-256 der vorhandenen APK erneut selbst berechnet: `9a1c1b7536d61070772a80073fd41f248e8988451a4884b2ae2eb54c4a7b9bab`. |

[Vergleich des Codecommits mit der aktuellen Arbeitsbranch-Spitze](https://github.com/kvmgithub/Spotilol/compare/18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac...59d18a1bd384bee1d547fb30263d9a2b04ec3062).

Die gemeldete Version 1.4.0, Diagnoseversion `v=3` und das Feld `dropped` passen zum geprüften Build. Diese Angaben allein identifizieren die auf dem Telefon installierte APK nicht kryptografisch. Die überprüfte Artefakt-Prüfsumme und die Zuordnung der Installation bleiben unterschiedliche Belege.

## 2. Korrekturen an den Auswertungen

### D1 – Ein manueller Reload ist vorhanden

In Gerätetest 1 steht, die App biete keinen manuellen Reload. Gerätetest 2 erklärt ebenfalls, ein Reload lasse sich nicht gezielt auslösen. Beides widerspricht dem geprüften Code:

- Der Eintrag „Player neu starten“ steht am Ende des Bereichs „App“ in den Einstellungen, unabhängig vom Diagnoseschalter.
- Ein Klick ruft direkt `MyspotiRuntime.host.reload()` auf.
- Der Host lädt die Playerseite neu oder ersetzt unter bestimmten Wiederherstellungsbedingungen die WebView. Der Aufruf ist kein Logout und kein Prozessneustart.
- Bei einem ausdrücklich entladenen Player im Datensparzustand führt `reload()` nichts aus. Für den Test daher von einer geladenen, lokal spielenden Playerseite ausgehen.

Fundstellen: [SettingsScreen.kt, Aufruf bei Zeile 233](https://github.com/kvmgithub/Spotilol/blob/18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac/base/app/src/main/java/com/music/spotui/ui/screens/SettingsScreen.kt#L233), [deutsche Beschriftung](https://github.com/kvmgithub/Spotilol/blob/18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac/base/app/src/main/res/values-de/myspoti_strings.xml#L80), [WebPlayerHost.reload](https://github.com/kvmgithub/Spotilol/blob/18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac/base/host-android/src/main/kotlin/app/myspoti/host/android/WebPlayerHost.kt#L499).

**Folge:** Dokumentation berichtigen. Keine neue Reload-Funktion und kein zusätzlicher Debugbuild erforderlich.

### D2 – Schalterfunktion und Wiedergabegleichheit getrennt bewerten

Gerätetest 2 beschreibt ein überzeugenderes Abschaltindiz als Durchgang 1: Während weiterer Antworten und Playeraktionen fehlen Diagnosezeilen; das Wiedereinschalten eröffnet einen neuen Lauf mit `reason=on`.

Das stützt die **Schalterfunktion**. Es ist jedoch kein vollständiger Gerätevergleich identischer Wiedergabeabläufe mit Diagnose an und aus. Der Bericht sagt selbst, dass die beiden Phasen unterschiedliche Schritte enthielten. Hörbare Gleichheit lässt sich aus Statusmeldungen nicht bestimmen.

Korrekte Ergebnisformulierung: **„Ein-/Ausschalten im beschriebenen Geräteablauf bestätigt; identischer Ablauf an/aus auf dem Gerät nicht vollständig verglichen.“** Die bereits geprüften automatisierten Vergleiche von Befehlen, Argumenten, Reihenfolge und Relay-Antworten bleiben davon unberührt.

### D3 – Der Hänger ist nicht sicher als unabhängig von M1 eingeordnet

Der Ablauf um 21:03–21:04 Uhr ist ein brauchbarer separater Fehlerhinweis: Werbung, Reload, `DEVICE_NOT_FOUND`, hängendes `player.resume`, wiederholtes Puffern und weiterer Reload. Die Diagnose war laut Auswertung ausgeschaltet.

Das schwächt den Verdacht gegen die aktive Diagnose, schließt eine M1-Regression aber nicht aus:

- Auch bei Diagnose aus durchlaufen Antworten den mit M1 ausgelagerten Schritt in `RelayAnswers`; auch die ergänzte Wrapper-Nachricht bleibt vorhanden.
- HTTP 200 bestätigt die erfolgreiche HTTP-Antwort. Es bestätigt weder die vollständige Funktionsfähigkeit des Players noch die Ursache eines späteren Stillstands.
- `ready=0` und ein hängender Befehl beschreiben den beobachteten Fehlerzustand. Sie lokalisieren dessen Ursache nicht zuverlässig.
- Die bereits geprüfte Gleichheit der Antwortverarbeitung spricht gegen bestimmte Regressionen, ersetzt aber keinen Vergleich dieses konkreten Geräteablaufs vor und nach M1.

Korrekte Einordnung: **„Wiedergabehänger bei ausgeschalteter Diagnose; Zusammenhang mit M1 ungeklärt, durch die vorliegenden Angaben nicht belegt.“** Die Überschrift „außerhalb von M1“ kann eine getrennte Bearbeitung bezeichnen, darf aber keine bewiesene Unabhängigkeit suggerieren. Daraus entsteht derzeit weder ein bestätigter M1-Blocker noch eine allgemeine Freigabe der Wiedergabestabilität.

### D4 – Reichweite der Messungen präzisieren

| Angabe | Belastbare Lesart |
| --- | --- |
| 40 Diagnosezeilen in Durchgang 1 | 23 Exportzeilen und 17 Logcat-Zeilen mit Überschneidung; keine 40 unabhängigen Beobachtungen. |
| 14 Diagnosezeilen in Durchgang 2 | Kleine erhaltene Stichprobe; frühere Zeilen fehlen auch hier. |
| Alle Zähler null | In den beschriebenen erhaltenen Zeilen kein gezählter Fehler oder Verlust. Kein Vollständigkeitsnachweis für den gesamten Test. |
| `seq=0–66` über rund 13,7 Minuten | Erlaubt keine unabhängige Prüfung aller früheren Zeilen oder der Ausgaberate in den verlorenen Zeitfenstern. Die harte Begrenzung wurde bereits separat getestet. |
| Hintergrund und Sperrbildschirm | Systemereignisse und Medienbenachrichtigungen stützen fortgesetzte Playeraktivität. Lückenlose hörbare Wiedergabe oder Klangqualität erfordern die unmittelbare Gerätebeobachtung. |
| Alte DELETE-Anfragen nach Logout erzeugen keine Diagnosezeile | Allein kein gezielter Nachweis der Abweisung alter relevanter Playback-Antworten. Diese Abweisung wird durch die bestehenden Tests geprüft. |
| `page=2` nach zwei Reloads bei Diagnose aus | Zweite vergebene Aktivierungskennung im Prozess, kein Zähler aller tatsächlichen Reloads. |
| „a song plays after the reload“ | Player-Logmeldung; das Wort „hörbar“ ist nur bei zusätzlicher tatsächlicher Hörbeobachtung gerechtfertigt. |

## 3. Abnahmestatus

Die Spalte „Gerät“ bewertet die vorgelegten Auswertungen, keine selbst erhobenen Rohdaten.

| Bereich | Gerät | Reviewentscheidung |
| --- | --- | --- |
| Skip, Kontext Playlist → Album, Ab-/Anmeldung | Plausible, mit dem Code vereinbare Abläufe beschrieben | Kein neuer Befund. |
| Natürlicher Titelwechsel mit Crossfade | Übergang und Kandidatenzuordnung beschrieben | Für M1 brauchbare Beobachtung; kein vollständiger Audiotest. |
| Pause/Fortsetzen, Hintergrund/Sperrbildschirm | Im Bericht positiv eingeordnet; beschränkte Logabdeckung | Keine Wiederholung des gesamten Durchgangs aus diesem Review erforderlich. |
| Diagnose aus/an und Abschalten | Schalterwirkung nachvollziehbar beschrieben | Schalterprüfung ausreichend gestützt; Gerätevergleich der Wiedergabegleichheit bleibt eingeschränkt. |
| Reload bei Diagnose aus | Zweimal beschrieben | Deckt den aktivierten Diagnose-Lebenszyklus nicht ab. |
| Reload bei Diagnose an, ohne Logout | Nicht dokumentiert | Offen auf dem Gerät; automatisiert bereits abgedeckt. Gezielter Check nach Abschnitt 4 genügt. |
| Datenschutz der neuen Diagnosezeilen | In den erhaltenen Zeilen laut Auswertungen keine Rohkennungen oder Geheimnisse | Positives Stichprobenergebnis; keine Aussage über alle vorhandenen Logpfade. |
| Frühere Codebefunde R1–R7 | Weiterhin geschlossen | Keine weitere M1-Codekorrektur aus diesen Berichten. |
| Werbung/Reload/Wiedergabehänger | Tatsächlicher Fehlerzustand beschrieben, Ursache offen | Separat nachverfolgen; nicht als bewiesenen Altfehler oder bewiesene M1-Regression behandeln. |

Die Reload-Abdeckung wurde im Code erneut nachvollzogen: `GainDiagnosticsTest` prüft das Verwerfen alter Seitenmeldungen und Antworten sowie die Herkunft wartender Zeilen über den Seitenwechsel. `RelayAnswersTest` prüft die Antwortzustellung an das ursprüngliche Dokument und die getrennte Diagnosezuordnung. Diese Tests laufen ohne reale Android-WebView und bleiben von der fehlenden Gerätebeobachtung zu unterscheiden.

Fundstellen: [GainDiagnosticsTest: alte Seite](https://github.com/kvmgithub/Spotilol/blob/18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac/base/api-spotify/src/test/kotlin/app/myspoti/spotify/GainDiagnosticsTest.kt#L309), [GainDiagnosticsTest: wartende Zeilen](https://github.com/kvmgithub/Spotilol/blob/18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac/base/api-spotify/src/test/kotlin/app/myspoti/spotify/GainDiagnosticsTest.kt#L356), [RelayAnswersTest: Reload](https://github.com/kvmgithub/Spotilol/blob/18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac/base/host-android/src/test/kotlin/app/myspoti/host/android/RelayAnswersTest.kt#L146).

## 4. Gezielter Abschluss des Reload-Punkts

Der verbleibende Gerätetest benötigt dieselbe APK und keine weiteren Funktionen:

1. Lokale Wiedergabe starten; Diagnose einschalten beziehungsweise eingeschaltet lassen. Den aktuellen Lauf und die aktuelle Seitenkennung im Export festhalten.
2. Unter **Einstellungen → App → Player neu starten** einmal neu laden. App-Prozess und Anmeldung beibehalten.
3. Nach erneuter Bereitschaft einmal den Titel wechseln und unmittelbar exportieren.

Erwartete Belege: tatsächlicher Seitenstart und erneute Bereitschaft; danach `ev=enable` mit neuer `page` bei unverändertem `run`; neue Titel-/Antwortbeobachtungen gehören zur neuen Seite. Ein reiner Reload eröffnet keinen Kontowechsel-Messlauf. `pl` und `el` dürfen auf der neuen Seite erneut bei 1 beginnen. Wenn ein echter Sitzungswechsel auftritt, ist dieser Versuch gesondert einzuordnen.

Alte, vor dem Reload bereits erfasste Ausgabezeilen dürfen nach der Drosselung noch mit ihrer **alten** Seitenkennung erscheinen. Das wäre kein Fehler. Ebenso ist `late>0` nicht automatisch ein Fehlschlag: Der Zähler kann gerade die korrekte Abweisung verspäteter Nachrichten anzeigen. Entscheidend sind Herkunft und Zuordnung.

Diese Anleitung schließt ausschließlich den fehlenden Gerätebeleg. Ohne diesen Versuch bleibt der Punkt ausdrücklich offen; er wird nicht nachträglich als bestanden markiert. **Die Auswertung für M2 muss darauf nicht warten.**

Der App-Export liest vorhandenen Logcat-Inhalt und begrenzt zusätzlich die Textmenge. „Last 10 min“ garantiert daher keine zehn vollständig erhaltenen Minuten. Sofortiger Export nach dem kurzen Ablauf ist wichtiger als ein weiterer langer Test. Dass das Exportmenü derzeit den eingeschalteten Diagnoseschalter verlangt, ist im Code bestätigt; für diesen Reload-Test entsteht dadurch kein zusätzliches Problem.

## 5. Was die Ergebnisse für M2 tragen

### `level` ist keine gemessene Audiolautheit

Im geprüften JavaScript gilt:

```text
level = el.volume × boost.out.gain.value   bei geroutetem Element
level = el.volume                        sonst
```

Das ist eine Kombination von Stellgrößen. Das Feld enthält weder eine RMS-/LUFS-Messung noch den gesamten Normalisierungs-, Boost-, Crossfade- und Limiterpfad oder die Android-Ausgabelautstärke. Ein Wert von 1 beweist deshalb weder unveränderte hörbare Lautheit noch, dass Spotify `gain_db` ignoriert.

Die niedrigen Werte beim Titelwechsel passen zu Momentaufnahmen einer Blende. Die Diagnose wird bei bestimmten Ereignissen ausgelöst; eine Änderung des Ausgangs-GainNode erzeugt nicht automatisch ein `volumechange` am Medienelement. Damit fehlt in den Berichten eine systematische Beobachtung nach Abschluss des Übergangs. Das ist eine Messgrenze, kein neuer M1-Implementierungsfehler.

Fundstellen: [gdRead](https://github.com/kvmgithub/Spotilol/blob/18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac/base/api-spotify/src/main/resources/bridge/player-bridge.js#L300), [levelOf](https://github.com/kvmgithub/Spotilol/blob/18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac/base/api-spotify/src/main/resources/bridge/player-bridge.js#L498).

### Die eigene Normalisierung ist im Code eindeutig

Die Berichte vermuten eine Obergrenze von +6 dB. Diese lässt sich direkt bestätigen:

```text
App-Gain = clamp(−10 − loudness, −12, +6) dB
```

Aus den berichteten Cachewerten ergeben sich:

| Titel | Berechneter App-Gain | Berichteter Spotify-Kandidat | Differenz, ungefähr |
| --- | ---: | ---: | ---: |
| Blackreach Rising | +1,889 dB | −3,3488 dB | 5,24 dB |
| Warhammer … Epic Version | +5,810 dB | −0,5182 dB | 6,33 dB |
| The Imperial March | +3,820 dB | 0,0000 dB | 3,82 dB |

Die Formel ist aus dem Code verifiziert; die Eingangswerte stammen aus Claudes Auswertungen. Diese Stichprobe liefert keine feste Umrechnungsregel zwischen beiden Größen. Sie belegt auch weder eine doppelte Normalisierung noch die tatsächliche Anwendung des Spotify-Feldes. Der zusätzliche Boost und der Limiter verhindern außerdem, die Zahlen einfach als gemessene Ausgangslautstärke zu lesen.

Fundstelle: [SpotifyPlayer.normalizeGain](https://github.com/kvmgithub/Spotilol/blob/18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac/base/api-spotify/src/main/kotlin/app/myspoti/spotify/SpotifyPlayer.kt#L2592).

Gleiche Werte der beobachteten `mp4`- und `mp4_dual`-Kandidaten erleichtern den Vergleich dieser vorhandenen Werte. Die tatsächlich abgespielte Datei wird dadurch weiterhin nicht identifiziert. Aliasse sind nur innerhalb ihrer Prozess-/Laufgrenzen sinnvoll zusammenzuführen; ein `t1` eines neuen Laufs ist nicht automatisch derselbe Titel wie ein früheres `t1`.

## 6. Übergabe an Claude

1. **M1-Code unverändert lassen.** Aus diesen beiden Auswertungen folgt keine weitere Korrekturrunde am Diagnosebaustein.
2. **Die Gerätetest-Dokumentation berichtigen:** vorhandener manueller Reload, eingeschränkter An/Aus-Vergleich, unbewiesene Ursache des Hängers sowie Grenzen von Zeilenzahl und Hörbarkeitsaussagen. Vorliegende Beobachtungen nicht um nachträglich behauptete Tests ergänzen.
3. **Den Reload-Nachweis getrennt führen.** Der kurze Ablauf aus Abschnitt 4 ersetzt eine erneute vollständige Testrunde. Ohne Ergebnis bleibt genau dieser Gerätepunkt offen.
4. **M2 mit der Auswertung und gezielten Zustands-/Zeitablauftests fortführen.** Die vorhandenen `ReloadTest` und `SpotifyPlayerCharacterizationTest` zuerst auf Abdeckung prüfen. Der gemeldete Ablauf rund um Werbung, Geräteanmeldung, verzögerte Befehle und Benutzerpause ist dafür ein konkreter Ausgangspunkt. Einen daraus abgeleiteten Test als Rekonstruktion kennzeichnen; er beweist die Geräteursache erst, wenn der Fehler tatsächlich reproduziert wird.
5. **Keine Gain-Regel aus diesen Daten implementieren.** Vor einer Nutzung von `gain_db` fehlen weiterhin Belege für Auswahl, Anwendung und Messstelle. Eine zusätzliche passive Messung nach Ende einer Blende wäre ein eigener begrenzter M2-Schritt; sie darf die bereits vereinbarten Grenzen für Aktivierung, Ausgabe, Speicher und Wiedergabeeinfluss nicht aufweichen.

Die technische M1-Freigabe bleibt gültig. Die Gerätebeobachtungen werden mit den genannten Einschränkungen übernommen. Merge und reguläre Veröffentlichung bleiben HKs Entscheidung; dieses Review führt beides nicht aus.
