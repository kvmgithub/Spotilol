# MySpotilol – Umsetzungsauftrag für Claude Opus 5.5

Stand: 13. November 2026. Dieser Auftrag ersetzt den bisherigen Nachtrag als Arbeitsanweisung. Die technischen Vorbehalte daraus sind unten berücksichtigt.

**Beginne jetzt mit Arbeitspaket M1.** Erstelle die Implementierung, die zugehörigen Tests und einen überprüfbaren CI-Build. Eine weitere grundsätzliche Diskussion der bereits entschiedenen Rollen und Reihenfolge ist nicht erforderlich. Technische Detailentscheidungen innerhalb dieses Umfangs triffst du selbst und dokumentierst sie in der Übergabe.

## 1. Rollen und Arbeitsablauf

| Beteiligter | Verantwortung |
| --- | --- |
| ChatGPT/Codex | Plant Arbeitspakete und Abnahmekriterien. Prüft anschließend den tatsächlichen Diff, relevante angrenzende Pfade und Prüfergebnisse. Arbeitet beim Review ausschließlich lesend am Repository. |
| Claude Opus 5.5 | Schreibt und ändert den Code, ergänzt Tests, führt Prüfungen aus, baut über CI und behebt bestätigte Reviewbefunde. Ist der einzige schreibende Implementierer dieses Pakets. |
| HK | Legt die Produktziele fest, übernimmt erforderliche Gerätetests und entscheidet über Integration und reguläre Veröffentlichung. |

Ablauf: **Auftrag → Implementierung → Prüfungen → Übergabe an ChatGPT/Codex → gegebenenfalls Korrekturen durch Claude → Integration.**

Die Übergabe erfolgt über diesen Auftrag, Branch, Commit-IDs, Diff und Prüfergebnisse. Es wird keine automatische Verbindung zwischen den beiden Agentensitzungen vorausgesetzt. Dieser Ablauf übernimmt die Rollenaufteilung und die überprüfbaren Übergaben aus dem [verlinkten Arbeitsmodell](https://promptworkbook.com/models/chatgpt-and-claude-code-together/).

Claude darf für M1 einen Arbeitsbranch anlegen und pushen sowie dessen CI ausführen. Kein direkter Push nach `myspoti-ui`, kein Merge und kein neuer regulärer Release innerhalb dieses Pakets. Behebungen bestätigter M1-Reviewbefunde benötigen keine erneute Grundsatzfreigabe. Bei einem echten Blocker bearbeitest du die davon unabhängigen Teile weiter.

## 2. Ziel und Ausgangsstand

**Ziel M1:** Die bestehende App kann bei aktivierter Diagnose beobachten, welche `gain_db`-Werte verfügbar sind, zu welchen Titeln und Dateikandidaten sie gehören und welche Lautstärkestellgrößen beim lokalen Player auftreten. Die Messung beeinflusst keine Wiedergabeentscheidung.

**Architektur:** Ein begrenzter, sitzungsbezogener Diagnosebaustein verbindet bereits verfügbare Antwortdaten mit Playerbeobachtungen. Die JavaScript-Bridge liefert ausschließlich Beobachtungen. Ausgabe und Bedienung verwenden die vorhandene appinterne Diagnose.

**Technik:** Kotlin/JVM, Android, vorhandene JSON-Verarbeitung, JavaScript/WebView, JUnit und GitHub Actions. Keine neue Laufzeitbibliothek für diese Diagnose.

Geprüfte Basis:

- Repository: [kvmgithub/Spotilol](https://github.com/kvmgithub/Spotilol).
- Produktbranch: `myspoti-ui`.
- Am 12.11.2026 erneut geprüfter HEAD: `64f97bb9ffe517b2f40ded45b7e934e23ec6a11a`, MySpotilol 1.4.0.
- Arbeitsbranch für dieses Paket: `work/gain-diagnostics`; falls bereits vorhanden, dessen Zustand prüfen und passende vorhandene Arbeit fortsetzen.

Vor dem ersten Eingriff liest du die geltenden Repository-Anweisungen und prüfst Arbeitsbaum, Branch sowie aktuelle Commit-ID. Falls der Produktbranch inzwischen weitergelaufen ist, integrierst du M1 auf dessen aktuellem Stand und dokumentierst die Abweichung. Bereits erledigte Teile nicht erneut implementieren. Fremde oder uncommittete Änderungen nicht überschreiben.

## 3. Grenzen für M1

- `gain_db` wird weder direkt noch als `gain_db + 4 dB` zur Lautstärkeregelung verwendet. Fehlende Werte werden nicht als null dB interpretiert.
- Normalisierung, Boost, Limiter, Crossfade, Werbung, Anmeldung, DRM und Netzwerkregeln behalten ihr bisheriges Verhalten.
- Keine Veränderung von Spotify-Antworten für einen A/B-Test; dieser Versuch gehört in ein späteres Paket.
- Keine zusätzlichen Spotify-Anfragen, Retries, neuen Hosts oder Erweiterungen der Relay-Abdeckung für die Diagnose. Insbesondere bleibt `fetch(Request)` zunächst unverändert.
- Keine neue AudioContext-Instanz, kein zusätzliches Routing von Medienelementen und kein Aktivieren eines bisher ausgeschalteten Audiographen nur zum Messen.
- Keine Einführung von Android Auto, Offlinewiedergabe oder zuvor entfernten Telemetriekomponenten.
- Paketname `com.project.lol.fork.ui`, Signierschlüssel und bestehende APK-Prüfungen bleiben erhalten. Kein Versionssprung nur für dieses Arbeitspaket.
- Kein nativer Player und keine Decoder-Instanziierung in M1. Eine Codec-Obergrenze wird erst zusammen mit dem tatsächlich eingesetzten nativen Decoder relevant.

## 4. Dateien und Verantwortlichkeiten

Die folgenden bestehenden Dateien wurden geprüft. Zusätzliche Änderungen an ihren unmittelbaren Aufrufstellen sind zulässig, wenn sie die Diagnoseverdrahtung erfordert; führe sie in der Übergabe einzeln auf.

| Bereich | Dateien und Aufgabe |
| --- | --- |
| Diagnosemodell und Korrelation | Neu: `base/api-spotify/src/main/kotlin/app/myspoti/spotify/GainDiagnostics.kt`. Aufnahme, Zuordnung, Begrenzung und freigegebene Logfelder zentral halten. |
| Vorhandene Audio-Features | `base/api-spotify/src/main/kotlin/app/myspoti/spotify/SpotifyPlayer.kt`: vorhandene Lautheitswerte beobachten, Diagnosezustand und Sitzungswechsel anbinden. |
| Bridge | `base/api-spotify/src/main/resources/bridge/player-bridge.js` und `base/api-spotify/src/main/kotlin/app/myspoti/spotify/bridge/Bridge.kt`: Beobachtungsnachrichten, Ein-/Ausschalten, Lebenszyklus. |
| Bereits empfangene Antworten | `base/host-android/src/main/kotlin/app/myspoti/host/android/ControlRelay.kt`: relevante vorhandene Wiedergabeantworten beobachten. Rückgabe und bisherige Verarbeitung unverändert lassen. |
| Bedienung und Export | `base/app/src/main/java/com/music/spotui/data/BetaLogs.kt` und notwendige bestehende Initialisierungsstellen: vorhandenen Diagnoseschalter tatsächlich bis zum Sammler durchreichen. |
| Tests | Neu: `base/api-spotify/src/test/kotlin/app/myspoti/spotify/GainDiagnosticsTest.kt`; bei Bedarf vorhandene `NormalizeTest.kt`, `bridge/BridgeMessagesTest.kt` und `security/LogRedactorTest.kt` ergänzen. JavaScript-Verhalten ohne Login in `base/api-spotify/bridge-tests/gain-diagnostics.test.mjs` prüfen. |
| CI und Übergabe | `.github/workflows/spotilol-ui.yml` nur für die neuen Prüfungen und das sichere Bereitstellen der Arbeitsbranch-Artefakte ergänzen. Auftrag und Ergebnis unter `docs/agent-handoff/` ablegen. |

Keine allgemeine Umstrukturierung der großen Playerdateien. Ein abweichender Name oder eine bereits vorhandene passende Abstraktion ist vertretbar, sofern die Zuständigkeiten erhalten bleiben und die Abweichung begründet wird.

## 5. Umsetzung von M1

### A. Diagnosebaustein und Zuordnung

- [ ] Zuerst Verhaltenstests für gültige, fehlende, ungültige und verspätete Beobachtungen schreiben. Die neuen Tests müssen zunächst wegen des fehlenden Verhaltens scheitern.
- [ ] Den Diagnosebaustein implementieren: explizit aktivierbar, mit injizierbarer Uhr und Logausgabe, ohne eigene Netzwerkzugriffe und ohne Zugriff auf Wiedergabebefehle.
- [ ] `gain_db` aus den bereits vorliegenden relevanten Wiedergabeantworten lesen. Titel, Dateikandidat, Format, Datenquelle und beobachteten Kontext zusammenhalten. Nicht jeden Eintrag einer Queue dem aktuell laufenden Titel zuordnen.
- [ ] Ein angebotener Dateikandidat ist keine belegte tatsächlich abgespielte Datei. Kennzeichnung `candidate`, `selected` oder `unknown` verwenden; `selected` nur bei einer bestehenden eindeutigen Auswahlquelle.
- [ ] `loudness` ausschließlich aus vorhandenen Cachewerten oder ohnehin stattfindenden Audio-Features-Antworten übernehmen. Bei deaktivierter Normalisierung kann der Wert fehlen; dafür keine neue Abfrage auslösen.
- [ ] Verspätete Antworten nach Skip, Gerätewechsel oder Logout dürfen keine falsche Zuordnung zum aktuellen Titel erzeugen. Sitzungen trennen, unbekannte Zuordnungen ausweisen.
- [ ] Fehlende Felder, explizites JSON-null und ungültige Zahlen unterscheidbar erfassen. Gültiges `0.0` erhalten. Keine stillschweigende Ersetzung durch `0` oder `1`.
- [ ] Die Tests ausführen und anschließend den zusammenhängenden Teil committen.

Verbindlicher Datenumfang für einen Diagnoseeintrag:

| Feldgruppe | Inhalt |
| --- | --- |
| Zuordnung | Sitzungs-ID, monotone Ereignisfolge, Zeitpunkt, lokale Mess-ID für Titel und Datei, Kandidat/Auswahlstatus |
| Metadaten | Format, beobachteter Kontexttyp, Quellenkennung; fehlende Informationen bleiben unbekannt |
| Lautheit | `gain_db` und `loudness` jeweils mit Verfügbarkeitsstatus |
| Player | rohes `_playerVolume` oder unbekannt, `_cubicVolume` oder unbekannt, `el.volume`, `muted`, lokales aktives Gerät und Element-ID |
| Appzustand | bestehende Einstellungen für Normalisierung, Boost, Crossfade und Stummschaltung; Anlass einer eigenen Pegeländerung, soweit unmittelbar bekannt |

Keine Behauptung über die Ursache einer Änderung allein aus zeitlicher Nähe ableiten. Die Zuordnung bezieht sich auf Beobachtungen, nicht auf eine unterstellte Wirkung von `gain_db`.

### B. Passive Bridge-Beobachtung

- [ ] JavaScript-Verhalten mit kontrollierten Medienelementen und virtueller Zeit testen: Diagnose aus, Ein-/Ausschalten, Titel-/Elementwechsel, fehlende Privatfelder und `volumechange`.
- [ ] Beobachtung beim Titel-/Elementwechsel und bei `volumechange` einrichten. Den vorhandenen Playerstatus zur Ergänzung nutzen; keine zusätzliche dauerhafte Pollingschleife hinzufügen.
- [ ] Den Rohwert `_playerVolume` gesondert lesen. `ownLevel()` liefert bei fehlendem Feld `1` und ist daher kein Verfügbarkeitsnachweis.
- [ ] Eigene Pegeländerungen über die bestehenden Stellen kenntlich machen, ohne Setter, Fades oder Mute-Verhalten umzuschreiben.
- [ ] Neue Bridge-Nachrichten als feste, begrenzte Struktur auswerten und vor dem generischen Raw-Logging in Bridge.kt abzweigen. Intern benötigte IDs vor der Ausgabe durch Mess-IDs ersetzen; unbekannte Felder nicht ungefiltert ins Log durchreichen.
- [ ] Nach Seitenneuladen den vorhandenen Diagnosezustand wiederherstellen; bei Abschalten, Logout und verworfenen Elementen Listener und wartende Ausgabe aufräumen. Kein mehrfaches Registrieren beim Resume.
- [ ] Die Tests ausführen und den Teil committen.

Bestehende Analyser sind für M1 nicht erforderlich. Insbesondere beweist `boost.routed.has(el)` keinen erfolgreichen Anschluss: Im geprüften Code wird das Element vor `createMediaElementSource()` eingetragen. Dieses Problem nicht durch eine verdeckte Änderung am Wiedergabepfad „mitbeheben“.

### C. Begrenzung, Datenschutz und Aus-Schalter

- [ ] Den vorhandenen Diagnoseschalter verwenden. `BetaLogs.enabled` ist im Ausgangsstand vor allem eine UI-Einstellung; nicht annehmen, dass damit bereits Erfassung und Verarbeitung abgeschaltet sind.
- [ ] Bei ausgeschalteter Diagnose keine zusätzliche Antwortanalyse, keine Diagnose-Listener und keine Diagnoseeinträge erzeugen. Bereits begonnene Erfassung beim Abschalten beenden.
- [ ] Nur ausdrücklich freigegebene Felder ausgeben. Keine Token, Cookies, Lizenz-/Stream-URLs, vollständigen JSON-Antworten, Accountdaten oder rohen Titel-/Datei-IDs im neuen Diagnoseformat. Lokale Mess-IDs dürfen nur innerhalb des Messlaufs korrelieren.
- [ ] Höchstens 256 Titel-/Datei-Zuordnungen vorhalten. Alte Einträge verdrängen; bei einem neuen Messlauf oder Logout leeren.
- [ ] Höchstens zwei Diagnosezeilen pro Sekunde ausgeben. Häufigere Ereignisse zusammenfassen und die Zahl zusammengefasster beziehungsweise verworfener Ereignisse mitzählen. Keine unbeschränkte Warteschlange.
- [ ] Diagnosefehler dürfen Antwortweitergabe und Wiedergabe nicht unterbrechen. Dafür nur den neuen Diagnosezweig isolieren; vorhandene Fehlerbehandlung nicht pauschal lockern.
- [ ] Datenschutz, Begrenzung und ausgeschalteten Zustand mit Verhaltenstests absichern.

Diese Mengenbegrenzungen sind Planungsvorgaben für M1. Der Testbericht muss Einschränkungen der Beobachtung ausweisen: Ein nicht erfasster Wert ist kein Beweis dafür, dass Spotify ihn nicht geliefert hat.

### D. CI-Build und Übergabe

- [ ] Bestehende Prüfungen weiterhin ausführen; neue Tests in den regulären Testlauf aufnehmen.
- [ ] Die JavaScript-Tests müssen das tatsächlich verwendete Diagnoseverhalten ausführen. Reine Suchtests nach Codezeichenfolgen oder eine zweite Kopie der Implementierung reichen nicht.
- [ ] Den bestehenden Workflow auf dem Arbeitsbranch per `workflow_dispatch` ausführen. Der geprüfte Workflow startet bei einem normalen Push nur auf `myspoti-ui`; ein Push des Arbeitsbranches allein ist kein CI-Nachweis.
- [ ] Signierte APK, SHA-256-Prüfsumme und Testberichte als herunterladbare Actions-Artefakte bereitstellen. Der aktuelle Workflow hat dafür noch keinen Upload-Schritt. Ergänzungen auf den Arbeitsbranch-Testlauf begrenzen; verwendete Actions auf einen geprüften vollständigen Commit pinnen.
- [ ] Veröffentlichung, Release-Notes und Branch-Bereinigung des Produktbranches dürfen durch den Arbeitsbranchlauf nicht ausgelöst werden. Fehlerausgaben dieses Testlaufs als Artefakt bereitstellen, statt den bestehenden `ui-ci-logs`-Branch zu überschreiben.
- [ ] Vorhandene Prüfungen von Signatur, Paketname, nicht debuggablem Release-Build und entfernten Komponenten beibehalten. Signiermaterial ausschließlich über die bestehende CI-Konfiguration verwenden.
- [ ] Den fertigen Stand mit exakter Basis- und Ergebnis-Commit-ID an ChatGPT/Codex übergeben. Bei fehlendem Zugang zu CI oder Signierung den konkreten Blocker ausweisen; keinen Ersatzschlüssel erzeugen und keine erfolgreiche APK-Prüfung behaupten.

## 6. Abnahme und Reviewfokus

| Prüfung | Erwartetes Ergebnis |
| --- | --- |
| Diagnose aus | Neue Erfassung und Ausgabe sind inaktiv; keine zusätzlichen Requests oder Timer. |
| Datenqualität | Gültiger Gain einschließlich `0.0`, fehlende Werte und Fehler bleiben unterscheidbar. |
| Zuordnung | Verzögerte Antworten und Dateialternativen werden nicht als aktuelle, gespielte Datei ausgegeben. |
| Kein Wiedergabeeingriff | Dieselbe Sequenz von Player-/Netzwerkereignissen erzeugt bei Diagnose an/aus dieselben Wiedergabebefehle und bestehenden Antwortinhalte. |
| Lebenszyklus | Einschalten, Ausschalten, Reload, Resume und Logout erzeugen keine doppelten Listener oder sitzungsübergreifenden Zuordnungen. |
| Fehler und Last | Fehlerhafte Daten, Ereignisflut und voller Speicherbestand bleiben begrenzt und beeinträchtigen den Hauptpfad nicht. |
| Datenschutz | Tests mit markierten Beispieltokens, Cookies, URLs und IDs zeigen keinen Durchfluss dieser Werte ins neue Diagnoseformat. |
| APK | CI-Ergebnis und Artefakt gehören zum übergebenen Commit; bestehender Signierer und Paketname sind geprüft. |

Mindestens diese Fälle in automatisierten Tests abdecken: zwei Dateiformate eines Titels; mehrere Titel derselben Antwort; Gain null/fehlend/ungültig/0; nicht geladene Audio-Features; verspätete Antwort nach Skip und nach Logout; Wechsel zu einem Connect-Gerät; fehlendes `_playerVolume`; Ein-/Ausschalten während ausstehender Ausgabe; 257 Zuordnungen; Ereignisflut; fehlerhafte Diagnose-Nachricht.

Prüfungen vom Repository aus:

```bash
python3 .github/scripts/test_ui_release_notes.py
node --test base/api-spotify/bridge-tests/gain-diagnostics.test.mjs
```

Im Verzeichnis `base`:

```bash
./gradlew :api-spotify:test --tests app.myspoti.spotify.GainDiagnosticsTest --no-daemon --console=plain
./gradlew testDebugUnitTest :api:test :api-spotify:test --no-daemon --console=plain -Pkotlin.incremental=false
```

Der signierte Release-Build läuft mit den bestehenden Versions- und Signierparametern des Workflows. Bei geänderten Testklassennamen die gezielte Testanweisung entsprechend mitführen. Der vorhandene Python-Bridge-Test benötigt einen angemeldeten echten Webplayer und verändert Playerzustand; er ist kein Ersatz für die isolierten Tests und nicht ungefragt gegen ein echtes Konto auszuführen.

Geräteprüfung durch HK: Diagnose aus/an, einige Titelwechsel, Pause/Fortsetzen, App in den Hintergrund und Bildschirm sperren, zurückkehren, Diagnose ausschalten und exportieren. Vorhandene Klangfunktionen unverändert lassen. Android Auto und Offlinewiedergabe gehören nicht zum Testumfang. Ein fehlender Gerätetest wird als offen bezeichnet und nicht durch erfolgreiche JVM-Tests ersetzt.

## 7. Übergabe von Claude an ChatGPT/Codex

Erstelle `docs/agent-handoff/M1-result.md` mit:

1. Basis-Commit, Ergebnis-Commit, Arbeitsbranch und Compare-/Diff-Link.
2. Geänderte Dateien und jeweiliger Zweck; Abweichungen von diesem Auftrag.
3. Ausgeführte Befehle, tatsächliche Ergebnisse, CI-Run-Link und dessen Commit-ID.
4. APK-Artefakt, SHA-256, Paketname und Ergebnis der vorhandenen Signaturprüfung.
5. Aktivierung der Diagnose und ein synthetisches Beispiel des neuen Logformats.
6. Zuordnung jeder Abnahmeanforderung zu Test oder ausdrücklich offenem Gerätetest.
7. Bekannte Beobachtungslücken und konkrete verbleibende Risiken.

Reviewbefunde beantwortest du je Punkt mit „behoben“, „mit Beleg widerlegt“ oder „offen“. Nach einer Korrektur erneut die betroffenen Prüfungen ausführen und den neuen Commit nennen. ChatGPT/Codex prüft den Code selbst; eine Zusammenfassung oder grüne CI allein ersetzt das Review nicht.

**M1 endet mit dieser Übergabe.** Es wird nicht ohne Auswertung in eine automatische Lautheitskorrektur oder den nativen Umbau übergegangen.

## 8. Anschlussarbeiten

| Paket | Ziel | Abhängigkeit |
| --- | --- | --- |
| M2 | Auswertung der Diagnose und gezielte reproduzierbare Zustands-/Zeitablauftests nach Orpheons Mustern | Review von M1 und reale Beobachtungen; bestehende Tests zuerst wiederverwenden. |
| M3 | Kleiner, abgegrenzter Port von `PlaybackLocks` mit Lebenszyklus- und Gerätetest | Eigener konkreter Plan auf dem dann aktuellen Stand; Nutzen und Akkuverhalten prüfen. |
| M4 | Standardmäßig deaktivierter nativer Audiopfad auf Orpheon-Basis bei erhaltener MySpotilol-Oberfläche und Härtung | Gesonderter Implementierungsplan nach den vorherigen Ergebnissen; ein Prototyp kann vor Orpheon final beginnen. |

Ein kontrollierter `gain_db`-A/B-Test gehört nur dann in M2, wenn die Daten eine weitere Nutzung des Felds rechtfertigen. Dabei denselben Abschnitt derselben Audioquelle verwenden, die tatsächlich beim Webplayer ankommende Änderung nachweisen und die Messstelle kalibrieren. Konstante Lautstärkefelder allein sind kein Nichtanwendungsbeweis.

Für M4 ist der geprüfte Orpheon-Ausgangspunkt `3e15fc827b4b390de7f8bc0460e6e1adaa0b904f` (`v1.0.0-beta.1`). Spätere Änderungen werden neu bewertet. Nativer Boost/Normalisierung und gleichzeitige Wiedergabe zweier geschützter Quellen sind getrennte Ziele. Crossfade ist keine Voraussetzung für den ersten nativen Wiedergabeversuch.
