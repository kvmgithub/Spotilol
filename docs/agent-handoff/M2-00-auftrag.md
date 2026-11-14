# MySpotilol – M2-Auswertung und nächster Auftrag für Claude

Stand: 14. November 2026.

**Beginne mit M2.1: steuerbare Bridge-Antworten und gezielte Regressionstests für Reload, hängendes Fortsetzen und Benutzerpause.** M1 ist aus Reviewsicht abgeschlossen. Für diesen Einstieg sind weder ein weiterer M1-Gerätetest noch eine Entscheidung über den nativen Player erforderlich.

**Ziel:** Die im Gerätelog beobachteten Fehlerübergänge reproduzierbar prüfen und belegte Verletzungen der bestehenden Wiedergabeabsicht mit kleinen Korrekturen beheben.

**Architektur:** Echte `Bridge` und echter `SpotifyPlayer` gegen einen steuerbaren `BridgeHost` im Test. Antworten, Fehler, Seitenwechsel und Playerzustände werden unabhängig ausgelöst. Die Android-Bedienungssteuerung bleibt als zusätzliche, ausdrücklich getrennte Prüfebene dokumentiert.

**Technik:** Bestehendes Kotlin/JVM, Coroutines, JUnit Jupiter im Modul `:api-spotify`, vorhandene JSON-Verarbeitung und CI.

**Grundlagen:** `MySpotilol-Umsetzungsauftrag-Claude-2026-11-13.md`, Abschnitt 8; `MySpotilol-M1-Abschluss-Reload-2026-11-14.md`; die beiden älteren Gerätetests und ihre Rohlogs. Dieser Auftrag konkretisiert den nächsten M2-Schritt. Er ersetzt die M1-Freigabe nicht.

Codex hat für diesen Nachtrag ausschließlich gelesen und keinen neuen Testlauf oder Reproduktionsnachweis erzeugt. Claude bleibt der Implementierer.

## 1. Aktueller Stand

| Gegenstand | Erneut gelesener Stand |
| --- | --- |
| Repository | [kvmgithub/Spotilol](https://github.com/kvmgithub/Spotilol) |
| Produktbranch `myspoti-ui` | `63bff9587c7f3de2a258cd8b8278b0c6e64d9994` |
| M1-Arbeitsbranch `work/gain-diagnostics` | `59d18a1bd384bee1d547fb30263d9a2b04ec3062` |
| Letzter geprüfter M1-Codecommit | `18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac` |
| Neue Implementierung seit dem M1-Review | Keine. Die spätere Arbeit auf dem M1-Branch besteht weiterhin aus Dokumenten. |

M2.1 auf einem eigenen Branch `work/player-recovery-tests` vom vollständigen M1-Stand beginnen. Falls der Branch bereits existiert oder M1 inzwischen integriert wurde, den tatsächlichen Stand abgleichen und bestehende Arbeit fortsetzen. Kein direkter Push auf den Produktbranch, kein Merge und kein regulärer Release in diesem Paket.

## 2. Was die Daten für M2 ergeben

### Gain-Auswertung

Der neue Reload-Ausschnitt zeigt vorhandene, nach Titel und Dateikandidat zugeordnete Werte. Er entscheidet weiterhin nicht, ob und wo der Webplayer diese Werte auf das Audiosignal anwendet.

Beispiele aus dem neuen Export:

| Beobachtung | Belastbare Aussage |
| --- | --- |
| Kandidat `0.0000` bei gecachter Lautheit `−17.292 dB` | Gültiger Nullwert und unabhängiger Lautheitswert liegen vor. |
| Kandidat `−0.3583` bei `−16.207 dB` | Die beiden Größen sind in dieser Beobachtung verschieden. |
| App meldet in beiden Fällen `gain 6.0 dB` | Passt zur vorhandenen Begrenzung `clamp(−10 − loudness, −12, +6)`. |
| Sehr kleines `level` beim Titelwechsel | Momentaufnahme während einer Blende; keine Messung der endgültigen Ausgangslautheit. |
| Kandidaten überleben den Reload mit ausgewiesener alter Herkunft | Vorgesehenes Cacheverhalten innerhalb desselben Messlaufs. |

**Entscheidung für M2.1:** Keine Anwendung von `gain_db`, keine feste Umrechnung, keine Veränderung von Spotify-Antworten und kein neuer A/B-Versuch. Die erfolgversprechende nächste Arbeit liegt bei den Zustands- und Zeitabläufen.

### Wiedergabehänger

Im älteren Gerätetest 2 ergibt sich folgende Folge am 13. November, Uhrzeiten MEZ:

| Zeitpunkt | Ereignis |
| --- | --- |
| 21:03:09.587 | Wiederherstellung während eines Werbezustands löst Reload aus. |
| 21:03:11.116 | Neue Seite meldet Bereitschaft. |
| 21:03:11.599–11.621 | `DEVICE_NOT_FOUND` und fehlschlagendes Fortsetzen mit `NoSuchDevice`. |
| 21:03:12.321 | Weiterer Fortsetzungsaufruf beginnt. |
| 21:03:24.195–24.206 | Neuer Next-Befehl; Meldung `transport stuck for 12918 ms`; älterer Aufruf nach 11.885 ms abgebrochen. |
| 21:03:32.468–34.868 | Pause und zwei Wiederholungen, während der gemeldete Playerzustand noch nicht entsprechend folgt. |
| 21:04:02.015–04.218 | Erneuter Seitenstart; danach bereiter, pausierter Zustand. |

Das ist eine Vorlage für Ereignisfolgen. Die Rohlogs beweisen weder den ursprünglichen Netzwerkfehler noch einen Zusammenhang mit CPU- oder Speicherdruck.

**Wichtige Codezuordnung:** `transport stuck` kommt aus [SongPlayer.transport](https://github.com/kvmgithub/Spotilol/blob/18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac/base/app/src/main/java/com/music/spotui/di/SongPlayer.kt#L818). Dort wird ein laufender Aufruf erst beim Eintreffen eines neueren Befehls abgebrochen, sofern er bereits mindestens 3.000 ms läuft. Die Meldung nach 12.918 ms bedeutet deshalb nicht, dass ein Drei-Sekunden-Timer versagt hat.

Der separate Timeout von 15.000 ms umfasst den eigentlichen Befehlsblock. Bereitschafts- und Geräteprüfungen liegen teilweise davor. Daraus darf kein pauschales Gesamtzeitlimit von 15 Sekunden für jeden Klick abgeleitet werden.

## 3. Konkrete Testlücken im aktuellen Code

| Bestehende Absicherung | Noch fehlende Kombination |
| --- | --- |
| `ReloadTest`: neue Seite statt alter Bereitschaft; Pause während Reload | Antworten werden im Fake sofort mit `ok=true, value=null` geliefert. Ein wirklich wartender oder fehlgeschlagener Fortsetzungsaufruf wird so nicht geprüft. |
| `SpotifyPlayerCharacterizationTest`: letzte Titelauswahl, Werbeende, Pause und Wiederherstellung | Dieselbe sofort erfolgreiche Antwortannahme; keine kontrollierte Kombination aus Fehler, Wiederholungsverzögerung und neuer Benutzerpause. |
| `BridgeMessagesTest`: Aufruf nach bereits verschwundener Seite schlägt fehl | Der Test beginnt den Aufruf erst nach `pageGone()`. Ein schon ausstehender Aufruf, dessen Seite anschließend verschwindet, benötigt einen eigenen Fall. |
| `BridgeMessagesTest`: Generation im erzeugten Script | Eine verspätete Ergebnisnachricht des alten Aufrufs während eines neuen Aufrufs benötigt einen Verhaltenstest. |
| `PageDevice`: Seitenbereitschaft und Geräteanmeldung getrennt | Im gelesenen Testbestand keine direkten Fälle für verzögerte Anmeldung und `commandLost()` in Verbindung mit einer Benutzerpause. |
| `SongPlayer`: Serialisierung, Ablösung und Pause-Wiederholungen | Kein direkter Test der gesamten Transportsteuerung. Tests von `SpotifyPlayer` allein prüfen diese Android-Fassade nicht mit. |

Ein besonders gezielter Prüfpunkt ist [SpotifyPlayer.command](https://github.com/kvmgithub/Spotilol/blob/18c323fa7efd2d422f07c1bfd1f3fcb9f2b64dac/base/api-spotify/src/main/kotlin/app/myspoti/spotify/SpotifyPlayer.kt#L1998): Nach bestimmten Bridge-Fehlern wartet die Methode und sendet erneut. Sie prüft an dieser Stelle keine inzwischen geänderte Benutzerabsicht. Mehrere Wiederherstellungspfade besitzen eigene Prüfungen davor oder danach. **Ob die konkrete Überschneidung eine unerwünschte Wiederholung erzeugt, muss der neue Test zeigen; sie ist hier noch kein reproduzierter Gerätefehler.**

## 4. Umfang und Grenzen

- Bestehenden Webplayer, echte Kotlin-Bridge und bestehende Playerlogik testen.
- Produktionsänderungen nur für durch die neuen Tests belegte Fehler und unmittelbar erforderliche, kleine Testzugänge. Korrektur und reine Testvorbereitung in getrennten Commits halten.
- Keine allgemeine Zerlegung von `SpotifyPlayer` oder `SongPlayer` in diesem ersten Schritt.
- Bestehende Timerwerte, Wiederholungsgrenzen, Audioregeln, Netzwerkregeln, Anmeldung und DRM beibehalten, soweit kein konkreter Befund eine eng begrenzte Korrektur erfordert.
- Keine neuen Spotify-Aufrufe, keine Echtkonto-Tests und keine Rohlog- oder Kontodaten als Testfixture.
- Android Auto, Offlinewiedergabe, `PlaybackLocks` und nativer Audiopfad bleiben außerhalb dieses Pakets.
- Paketname `com.project.lol.fork.ui`, Signierer und bestehende APK-Prüfungen beibehalten.

Aus [Orpheons SessionTest am geprüften Stand](https://github.com/kota-zo/Orpheon/blob/3e15fc827b4b390de7f8bc0460e6e1adaa0b904f/base/engine/src/jvmTest/kotlin/app/myspoti/engine/SessionTest.kt#L18) wird das Testprinzip übernommen: Eingangsereignisse kontrollieren und ausgehende Befehle einschließlich Reihenfolge prüfen. Eine Befehlsquittung ersetzt keinen beobachteten Playerzustand. Orpheons native Engine wird dafür nicht importiert.

**Zeitsteuerung:** Orpheon verwendet zusätzlich virtuelle Coroutine-Zeit und eine injizierte Uhr. MySpotilols vorhandene Tests und Player verwenden teilweise echte Uhrzeit. Ein bloßer Wechsel auf `runTest` wäre keine vollständige Umstellung. Für M2.1 zunächst ereignisgesteuerte Freigaben und begrenzte Wartebedingungen verwenden. Reale Backoff-Fristen dürfen in diesen wenigen Tests bestehen bleiben; keine künstlich verkürzten Produktionskonstanten und keine gesamte Uhrumstellung als Nebenaufgabe.

## 5. Aufgabe A – Steuerbarer Testhost

**Dateien:**

- Neu: `base/api-spotify/src/test/kotlin/app/myspoti/spotify/bridge/ScriptedBridgeHost.kt`
- Erweitern: `base/api-spotify/src/test/kotlin/app/myspoti/spotify/bridge/BridgeMessagesTest.kt`

**Vertrag des Testhilfsmittels:**

```kotlin
data class CapturedCall(
    val id: Int,
    val page: Int,
    val method: String,
    val args: JsonObject,
)

class ScriptedBridgeHost : BridgeHost {
    suspend fun awaitCall(method: String): CapturedCall
    suspend fun succeed(call: CapturedCall, value: JsonElement = JsonNull)
    suspend fun fail(call: CapturedCall, message: String)
    suspend fun post(message: JsonObject)
}
```

`awaitCall` liefert den nächsten noch nicht abgeholten Aufruf dieser Methode. Aufrufe bleiben bis zur ausdrücklichen Antwort offen. Historie und Antworten sind nach Call-ID getrennt; mehrere gleichnamige Aufrufe müssen unabhängig steuerbar sein. Das Hilfsmittel liest die erzeugte Aufrufhülle, implementiert aber keine zweite Player- oder Retrylogik. Seitenbereitschaft, Zustandsnachricht und Call-Ergebnis bleiben separate Ereignisse.

- [ ] Den Hilfstest „zwei gleichnamige Aufrufe lassen sich in umgekehrter Reihenfolge beantworten“ schreiben. Prüfen, dass jeder Wartende genau seinen Wert erhält.
- [ ] Den Host ergänzen, bis dieser Test besteht. Für die Initialisierung auf den tatsächlichen Nachrichtenabonnenten warten.
- [ ] Laufende Bridge-Aufrufe nicht pauschal mit Erfolg quittieren. Für benötigte Hintergrundmethoden ausdrücklich gültige Antworten im Szenario definieren.
- [ ] Die Fälle B1–B3 aus der folgenden Tabelle gegen die echte `Bridge` ergänzen.
- [ ] Tests gezielt ausführen und den zusammenhängenden Testaufbau committen.

| Test | Ereignisfolge | Verbindliche Prüfung |
| --- | --- | --- |
| B1 `pendingCallFailsWhenPageGoesAway` | Aufruf erfasst und unbeantwortet → `pageGone()` | Der ausstehende Aufruf endet mit Seitenfehler, ohne seinen normalen Antworttimeout abzuwarten. Neue Seite kann anschließend antworten. |
| B2 `lateOldResultCannotCompleteNewCall` | Alter Aufruf → Seitenwechsel → neuer Aufruf → alte Erfolgsnachricht → neue Erfolgsnachricht | Alte Nachricht beendet den neuen Aufruf nicht; dessen eigener Wert kommt erst mit seiner eigenen Antwort. |
| B3 `cancelledCallDoesNotBreakNextCall` | Aufruf hängt → aufrufenden Job abbrechen → verspätete Antwort → neuer Aufruf | Abbruch bleibt Abbruch. Keine automatische Wiederholung durch die Bridge; neuer Aufruf funktioniert. |

Vorhandene Tests zu fehlerhaftem JSON und Generationserzeugung behalten. B1–B3 ergänzen deren fehlende Überlappungen.

## 6. Aufgabe B – Playerabläufe und minimale Fehlerkorrekturen

**Dateien:**

- Neu: `base/api-spotify/src/test/kotlin/app/myspoti/spotify/PlayerRecoverySequenceTest.kt`
- Wiederverwenden: `ReloadTest.kt` und `SpotifyPlayerCharacterizationTest.kt`
- Nur bei belegtem Fehler ändern: `base/api-spotify/src/main/kotlin/app/myspoti/spotify/SpotifyPlayer.kt`
- Bei dafür notwendiger kleiner Anpassung: `bridge/Bridge.kt` beziehungsweise `bridge/PageDevice.kt`; Umfang in der Übergabe begründen.

**Eingänge:** Testhost aus Aufgabe A; öffentliche `resume()`-, `pause()`-, `play()`- und Reload-Einstiege; echte Bridge-Nachrichten. **Ausgänge:** vollständige beobachtete Befehlsfolge, Ergebnisse der aufrufenden Jobs und öffentlich sichtbarer Playerzustand.

| Test | Kontrollierte Folge | Erwartetes Verhalten |
| --- | --- | --- |
| P1 `transientResumeFailureRetriesAndRecovers` | Fortsetzen → Bridge-Fehler `No such device` → nächste Anfrage erfolgreich → erst danach spielender Zustand | Vorgesehene Wiederholung gelingt; keine zusätzlichen Wiederholungen nach Erfolg. Erfolg allein erzeugt keinen erfundenen Wiedergabestatus. |
| P2 `pauseDuringResumeBackoffPreventsObsoleteRetry` | Fortsetzen → vorübergehender Fehler → Pause vor dem erneuten Senden | Nach der Pause wird kein weiterer veralteter Fortsetzungsversuch gesendet. Eine spätere neue ausdrückliche Fortsetzung muss wieder funktionieren. |
| P3 `pauseWhileReloadRestoreWaitsRemainsAuthoritative` | Laufender Titel → Seite weg → neue Seite bereit, Geräte-/Zustandsantwort verzögert → Benutzerpause → ausstehende Antworten freigeben | Wiederherstellung sendet anschließend keinen neuen automatischen Play-/Resume-Befehl für die überholte Absicht. |
| P4 `readyWithoutJoinedDeviceIsNotPlaybackConfirmation` | Seite bereit → Geräteanmeldung bleibt aus → Anmeldung beziehungsweise Fehler wird später gemeldet | Bereitschaft und Call-Erfolg werden nicht als tatsächliche Wiedergabe bestätigt. Vorhandene Warte- und Wiederholungsgrenzen werden eingehalten. |
| P5 `newPickSupersedesOlderRestore` | Wiederherstellung wartet → neuer Titel wird gewählt → alte Antwort trifft ein | Kein anschließender neuer Restore-Befehl verdrängt die neuere Auswahl. Prüfen, welcher Titel tatsächlich angefordert wird. |

P2 und P3 jeweils mit zwei Reihenfolgen prüfen: Pause vor der Fehler-/Antwortzustellung und Pause nach deren Verarbeitung, aber vor dem nächsten Wiederholungsversuch.

Bereits an die Seite gesendete Befehle lassen sich durch einen Test nicht rückwirkend zurücknehmen. Die Assertions beziehen sich auf **neu ausgesandte Folgekommandos und die Verarbeitung späterer Ergebnisse**. Ein späterer realer Zustand darf nicht einfach unterdrückt werden, um den Test grün erscheinen zu lassen.

- [ ] P1 als positive Referenz aufbauen.
- [ ] P2 und P3 vor jeder Produktionskorrektur ausführen. Das tatsächliche Ergebnis festhalten; einen grünen Test nicht als reproduzierten Fehler ausgeben.
- [ ] Bei einem roten Verhaltenstest die kleinste verantwortliche Stelle bestimmen. Bereits vorhandene Pausen-/Aktionskennungen bevorzugen; keine konkurrierende globale Zustandsmaschine einführen.
- [ ] Eine nachgewiesen überholte Wiederholung vor dem nächsten Senden verwerfen. Coroutine-Abbruch nicht als Erfolg behandeln und nicht durch allgemeines `runCatching` verdecken.
- [ ] P4 und P5 ergänzen und die positiven Gegenfälle erhalten: ohne neuere Benutzeraktion darf die vorgesehene Wiederherstellung weiterlaufen.
- [ ] Nach jeder Korrektur den betroffenen Test und die angrenzenden bestehenden Reload-/Charakterisierungstests ausführen.
- [ ] Testvorbereitung und funktionale Korrektur getrennt committen.

`PageDevice`, `PageAuth` und `AdBlock` enthalten prozessweiten Zustand. Szenarien müssen diesen nachvollziehbar initialisieren und bereinigen. Kein Test darf nur wegen einer bestimmten Ausführungsreihenfolge bestehen. Falls dafür ein kleiner expliziter Reset oder eine isolierbare Zustandsinstanz erforderlich ist, diesen Testzugang begrenzen und dokumentieren; keine Reflexionszugriffe auf private Felder.

## 7. Prüffokus und verbleibende Ebene

Die Aufgaben decken fünf besonders relevante Fehlerklassen ab: ausstehender Aufruf beim Seitenwechsel (B1), verspätete Antwort (B2), Abbruch (B3), neue Pause während Wiederherstellung (P2/P3) und neue Auswahl beziehungsweise verspätete Geräteanmeldung (P4/P5).

**Die Android-Fassade `SongPlayer` ist damit noch nicht vollständig getestet.** Ihre Transportwarteschlange und `ensurePaused()` liegen oberhalb von `SpotifyPlayer`. Insbesondere kommen die Pause-Wiederholungen nach jeweils 1.200 ms; die Abbruchschwelle für einen laufenden Transport wird nur bei einem neuen Auftrag geprüft. Dafür gehört in die M2-Übergabe ein gesonderter Prüffall:

- Laufender, nicht antwortender Transport.
- Pause und ihre beiden Wiederholungen treffen ein, während der ursprüngliche Aufruf beim jeweiligen Eingang noch unter 3.000 ms alt ist.
- Prüfen, wann der tatsächlich neueste Pauseauftrag ausgeführt wird und ob ein später alter Abschluss die Bedienungsanzeige verändert.

Diese Konstellation ist eine **zu prüfende Hypothese**, keine aus dem Gerätelog bewiesene Ursache. M2.1 ändert die Drei-Sekunden-Regel nicht stillschweigend. Falls die verbleibende Ursache in dieser Ebene liegt, soll Claude die konkrete Folge und den kleinsten notwendigen Testzugang für M2.2 ausweisen. Eine reine Nachbildung der Transportlogik im Test genügt nicht als Prüfung von `SongPlayer`.

Die bereits belegte Exportlücke für `accountId` und `displayName` bleibt ein separater Härtungsauftrag. Sie darf weder als neue M1-Regression noch als behobener M2-Befund aufgeführt werden, solange keine entsprechende Änderung geprüft wurde.

## 8. Prüfungen und Übergabe

Gezielt im Verzeichnis `base`:

```bash
./gradlew :api-spotify:test --tests app.myspoti.spotify.bridge.BridgeMessagesTest --tests app.myspoti.spotify.PlayerRecoverySequenceTest --tests app.myspoti.spotify.ReloadTest --tests app.myspoti.spotify.SpotifyPlayerCharacterizationTest --no-daemon --console=plain
```

Vor Übergabe die bestehenden Prüfungen vom Repository aus:

```bash
python3 .github/scripts/test_ui_release_notes.py
node --test base/api-spotify/bridge-tests/gain-diagnostics.test.mjs
```

Und im Verzeichnis `base`:

```bash
./gradlew testDebugUnitTest :api:test :api-spotify:test --no-daemon --console=plain -Pkotlin.incremental=false
```

Keine neue Testbibliothek ist für die beschriebenen ereignisgesteuerten Fälle zwingend erforderlich. Falls später `kotlinx-coroutines-test` verwendet wird, dessen Version anhand der tatsächlich aufgelösten Coroutines-Abhängigkeiten wählen: Der Katalog nennt derzeit 1.11.0, die App enthält zusätzlich eine direkte Android-Abhängigkeit 1.10.2. Nur eine Testabhängigkeit ergänzen; keine ungeprüfte Aktualisierung der Produktionsabhängigkeiten.

Übergabe unter `docs/agent-handoff/M2-01-result.md`:

1. Basis- und Ergebniscommit, Arbeitsbranch und Compare-Link.
2. B1–B3 und P1–P5 jeweils mit tatsächlichem Ergebnis und Testfundstelle.
3. Befundstatus getrennt: **reproduzierter Codefehler**, **bestandenes Zusatzszenario**, **offene Geräteursache**.
4. Vorher-/Nachher-Befehlsfolge jeder funktionalen Korrektur mit synthetischen IDs.
5. Geänderte Produktionsdateien und Begründung; unverändert gebliebene Grenzwerte.
6. Prüfkommandos und tatsächliche Ergebnisse; verbleibende `SongPlayer`-Abdeckung.
7. Bei Produktionsänderung: CI-Lauf für den exakten Ergebniscommit, signierte Arbeitsbranch-APK, SHA-256 und bestehende APK-Prüfungen. Bei ausschließlich neuen Tests reicht der Testnachweis; kein neuer Gerätedurchgang allein dafür.

Die vorhandenen Geräteprotokolle werden weiterverwendet. Ein erneuter Gerätetest wird erst aus einer konkreten funktionalen Korrektur und deren Wirkung abgeleitet. Codex prüft anschließend Diff, Tests und die angrenzenden Pfade; Integration und regulärer Release bleiben HKs Entscheidung.

