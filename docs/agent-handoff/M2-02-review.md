# MySpotilol – Review von M2.1

Stand: 14. November 2026. Grundlage: Claudes Ergebnisdatei, der tatsächliche Diff, die Originalberichte der beiden CI-Läufe und eigene JVM-Tests am unveränderten Code.

**Entscheidung: M2.1 noch nicht integrieren.** Die vier ursprünglichen Fehlertests bestehen mit Claudes Korrektur. Zwei zusätzliche Tests zeigen aber, dass eine alte Wiederherstellung weiterhin einen Positionssprung nach einer Pause auslösen oder eine neuere Titelauswahl überschreiben kann. Beide Fälle betreffen denselben Wiedergabepfad und gehören vor M2.2 korrigiert. M1 bleibt aus Reviewsicht abgeschlossen.

## Geprüfter Stand

| Bezug | Commit |
| --- | --- |
| Basis von M2.1 auf `work/gain-diagnostics` | `4504cde03978154e9dccede180440ff910b8a915`; seit dem geprüften M1-Code nur Dokumente |
| Stand mit endgültigem Testaufbau vor der Produktionskorrektur | `d644efe442dffe6d0b7b7a2c0b608527d8178383` |
| Produktionskorrektur und geprüfte APK | `4656fc38f67512a6439f50cebf795a65e727b339` |
| Spitze von `work/player-recovery-tests` beim Abruf | `9de96ded1f80cf8dfa09494eb8e5a597ead929a0`; nach der Korrektur nur der Ergebnisbericht |
| Produktbranch `myspoti-ui` beim Abruf | `63bff9587c7f3de2a258cd8b8278b0c6e64d9994`; M2.1 nicht integriert |

[Gesamtdiff M2.1](https://github.com/kvmgithub/Spotilol/compare/4504cde03978154e9dccede180440ff910b8a915...4656fc38f67512a6439f50cebf795a65e727b339) · [Produktionskorrektur](https://github.com/kvmgithub/Spotilol/commit/4656fc38f67512a6439f50cebf795a65e727b339)

Der Repository-Bericht stimmt inhaltlich mit der hochgeladenen Datei überein. Die GitHub-Stände wurden gelesen; Testaufbau und Prüfausgaben liegen außerhalb des Repositorys. Keine Änderung, kein Push, kein Merge, kein Release.

## R1 – Alter Wiederanlauf überschreibt einen neu gewählten Titel

**Reproduziert auf `4656fc3`. Bestehende Lücke im Rahmen von F2, keine neu eingeführte Regression.**

1. Die neu geladene Seite meldet den zuvor laufenden Titel A als pausiert. Die Wiederherstellung sendet `player.resume`; die Antwort bleibt ausstehend.
2. Der Benutzer wählt P. `player.play(P)` wird bestätigt, die Seite meldet P als spielend.
3. Der alte Aufruf für A wird erst danach bestätigt.
4. Nach der Prüffrist sendet die Wiederherstellung einen neuen `player.play(A)`.

Tatsächliche Befehlsfolge aus der Zusatzprobe, mit synthetischen Titeln:

```text
player.resume {}
player.play {contextUri: spotify:playlist:C, trackUri: spotify:track:P}
player.play {contextUri: spotify:playlist:C, trackUri: spotify:track:A, positionMs: 98001}
```

Die Korrektur erfasst `lastPlay` nun vor der Gerätewartezeit. Dadurch bleibt eine Auswahl **vor** dem ersten Wiederherstellungsbefehl erhalten. Nach einem suspendierenden Befehl setzt `resumeAfterReload()` seine Vergleichskennung aber erneut auf das **aktuelle** `lastPlay`. In dieser Probe ist das schon die neuere Benutzerauswahl P. Der nächste Versuch hält sie deshalb fälschlich für einen Teil seiner eigenen Wiederherstellung und sendet A erneut. Die Probe erzeugt denselben zusätzlichen Play-Befehl auch auf `d644efe`.

Fundstellen: [früher erfasste Kennung](https://github.com/kvmgithub/Spotilol/blob/4656fc38f67512a6439f50cebf795a65e727b339/base/api-spotify/src/main/kotlin/app/myspoti/spotify/SpotifyPlayer.kt#L381), [Übernahme des aktuellen lastPlay nach der Antwort](https://github.com/kvmgithub/Spotilol/blob/4656fc38f67512a6439f50cebf795a65e727b339/base/api-spotify/src/main/kotlin/app/myspoti/spotify/SpotifyPlayer.kt#L718).

**Abnahme:** P bleibt nach Zustellung der alten Antwort und Ablauf der Wiederherstellungsfrist die neueste Auswahl. Kein weiterer Play-, Resume- oder Seek-Befehl aus der alten Wiederherstellung. Die Kennung einer eigenen Aktion muss dieser Aktion zugeordnet bleiben; eine inzwischen eingetroffene fremde Auswahl darf nicht als eigene Kennung übernommen werden.

## R2 – Verworfener Resume-Versuch löst trotzdem einen Seek aus

**Reproduziert auf `4656fc3`. Folge des neuen Rückgabepfads für F1.**

1. Titel A lief vor dem Reload bei ungefähr 98 s. Die neue Seite meldet denselben Titel pausiert bei 0 s.
2. Die Wiederherstellung sendet `player.resume`. Die Bridge antwortet mit `No such device.`.
3. Der Benutzer pausiert während der Verzögerung vor der Wiederholung. Weil die Seite bereits pausiert meldet, muss kein eigener Pausenbefehl gesendet werden.
4. Die Wiederholung von `player.resume` wird korrekt unterdrückt. Trotzdem folgt ein neuer Positionsbefehl.

Tatsächliche Befehlsfolge auf dem geprüften Commit:

```text
player.resume {}
player.seek {"positionMs":98008}
```

Der neue Abbruchpfad liefert `JsonNull`. Für `resumePage()` ist das eine normale Rückkehr, genauso wie eine regulär beantwortete Fortsetzung. Deshalb führt `resumeAfterReload()` bei mehr als 5.000 ms Positionsabweichung unmittelbar den Seek aus. Seine Pausenprüfung am Anfang des nächsten Versuchs kommt dafür zu spät. Vor der Korrektur scheitert dieselbe Probe an einem weiteren Resume; nach der Korrektur scheitert sie an dem neu gesendeten Seek. In beiden Fällen ist die Benutzerpause allein kein Beleg, dass die Wiederherstellung keine weiteren Befehle sendet.

Fundstellen: [Rückgabe JsonNull beim Verwerfen](https://github.com/kvmgithub/Spotilol/blob/4656fc38f67512a6439f50cebf795a65e727b339/base/api-spotify/src/main/kotlin/app/myspoti/spotify/SpotifyPlayer.kt#L2023), [Seek nach normaler Rückkehr](https://github.com/kvmgithub/Spotilol/blob/4656fc38f67512a6439f50cebf795a65e727b339/base/api-spotify/src/main/kotlin/app/myspoti/spotify/SpotifyPlayer.kt#L705).

**Abnahme:** Ein verworfener Fortsetzungsversuch führt zu keinem Positionsbefehl und keinem weiteren automatischen Abspielversuch. Der interne Aufrufer kann „verworfen“ von „beantwortet“ unterscheiden. Der Gegenfall bleibt erhalten: Ohne neuere Benutzeraktion und mit bestätigter Fortsetzung korrigiert der Player die abweichende Position genau einmal. Diese Positivprobe besteht bereits.

Das ist ein nachgewiesener zusätzlicher **Befehl**, kein Nachweis, dass Spotify den Positionssprung auf einem Telefon tatsächlich angewendet hat.

## Was an M2.1 bestätigt ist

- **B1–B3:** Die echte Bridge scheitert beim Seitenverlust für einen bereits ausstehenden Aufruf, ignoriert ein altes Ergebnis für einen neuen Aufruf und bleibt nach Abbruch benutzbar. Der Testhost steuert Antworten nach Call-ID, ohne eine zweite Playerlogik nachzubilden.
- **P2a, P2b, P3b und P5a:** Die ursprünglichen Fehlerszenarien sind unabhängig bestätigt. Mit identischen Testdateien auf `d644efe` und `4656fc3` ändert sich nur `SpotifyPlayer.kt`. Vor der Korrektur scheitern genau diese vier der neun neuen Playerfälle, danach bestehen alle neun. Die Vorher-/Nachher-Aussage hängt daher nicht von Claudes zusätzlicher Änderung am Testaufbau ab.
- **P4:** Geprüft werden verspätete Geräteanmeldung, die Wartefristen und die Trennung zwischen Call-Erfolg und tatsächlichem Playerzustand. Der Fall injiziert keinen asynchronen Relay-Fehler über `PageDevice.commandLost()`. Ein entsprechender Relay-Ablauf bleibt eine eigene Prüfung.
- **Teststabilität:** Der rote CI-Lauf zeigt zusätzlich `PickDuringAdTest: timed out: ad flag`. Ein verlorenes Ereignis vor Abonnement ist mit dem Testaufbau vereinbar. Die einzelne Fehlermeldung beweist diese Ursache nicht allein. Die neuen Tests wiederholen Zustandsmeldungen, bis sie beobachtet werden. Auch dort garantiert das Lesen von `bridge.ready.value` noch nicht, dass der getrennte Sammler den Übergang verarbeitet hat. Für die neuen Überlappungen möglichst auf ein beobachtetes Ereignis oder den konkreten ausstehenden Aufruf warten.

**Der Gerätehänger vom 13. November bleibt offen.** Die Tests belegen fehlerhafte Befehlsfolgen unter kontrollierten Bedingungen. Sie beweisen weder deren Auftreten im damaligen Gerätelauf noch, an welcher Stelle damals die ausstehende Antwort verloren ging. Android-WebView und die darüberliegende Transportsteuerung `SongPlayer` waren in den JVM-Proben nicht beteiligt. Ein erfolgreicher B1–B3-Lauf schließt einen Fehler in diesen Schichten nicht aus.

## Prüfnachweise

| Prüfung | Festgestelltes Ergebnis |
| --- | --- |
| [Rote CI, 43548649414](https://github.com/kvmgithub/Spotilol/actions/runs/43548649414), `8c45cd3` | Originale JUnit-XML: 468 Tests in 73 Suiten, fünf Fehlschläge. Vier neue Playerfälle mit zusätzlichen unerwünschten Befehlen, ein bestehender Test mit Timeout. |
| [Grüne CI, 43549470285](https://github.com/kvmgithub/Spotilol/actions/runs/43549470285), `4656fc3` | Originale JUnit-XML: 468 Tests in 73 Suiten, keine Fehlversuche, Fehler oder übersprungenen Tests. Davon 268 in `:api-spotify`, 155 in `:app`, 41 in `:host-android`, vier in `:api`. Signierter Build erfolgreich; Release-Schritt übersprungen. |
| Eigener JVM-Lauf vor der Produktionskorrektur, `d644efe` | Neun `PlayerRecoverySequenceTest`-Fälle, fünf bestanden und vier erwartete Fehlschläge. |
| Eigener JVM-Lauf nach der Produktionskorrektur, `4656fc3` | 31/31 bestanden: Player-Recovery, Bridge, Reload und Charakterisierung. Kotlin 2.4.0 und Coroutines 1.11.0; direkte JVM-Kompilierung, kein lokaler Android-Build. |
| Eigene Zusatzproben, `4656fc3` | R1 und R2 reproduziert, Gegenprobe ohne neuere Benutzeraktion bestanden. Synthetische Bridge-Nachrichten und steuerbare Coroutine-Verzögerungen; keine Änderung an der Produktionsuhr. |
| Weitere lokale Prüfungen | 23/23 Node-Tests, Release-Notes-Prüfung bestanden. Die kompilierten Quellstände wurden zuvor gegen die Git-Blobs abgeglichen. |

**APK:** Die heruntergeladene Datei `MySpotilol-arm64-1.4.0.apk` ergab selbst berechnet SHA-256 `15c7a914572b85e83aa2590b1e4dfa2b69df208619e3bd4c4e1e0fdb8f650637`. Die enthaltenen nativen Bibliotheken sind ARM64. Die CI-Metadaten bestätigen `com.project.lol.fork.ui`, Version `1.4.0` / `1004000` und den erwarteten Signierer `73cf309a74b1a107a1e1cbb9453806f7b0d8e803957ecd371cb51cfed0b21e9a`. Die Signaturprüfung wurde in CI verifiziert; lokal wurde keine zweite `apksigner`-Prüfung ausgeführt.

## Nacharbeit für Claude

1. **Zwei gezielte Regressionstests für R1 und R2 ergänzen.** Den Fixstand `4656fc3` als roten Ausgangspunkt verwenden. Bei R1 die Positionen höchstens 5 s auseinander setzen, damit der unerwünschte Play-Befehl unabhängig vom Seek-Fall geprüft wird. Bei R2 die Positionsabweichung deutlich über 5 s setzen. Die Positivprobe mit Positionskorrektur und die bisherigen 13 neuen Tests erhalten.
2. **Die Wiederherstellung nach den maßgeblichen Wartepunkten erneut auf ihre Gültigkeit prüfen.** Vor einem Seek oder einem neuen Versuch muss klar sein, ob Pause oder Titelauswahl inzwischen Vorrang haben. Einen verworfenen Resume nicht als beantworteten Befehl behandeln. Bei der Wiederholung die Kennung des eigenen Befehls festhalten, statt nach der Antwort den dann aktuellen Benutzerbefehl zu übernehmen. Die öffentliche Player-Schnittstelle und bestehenden Fristen beibehalten.
3. **Die Reihenfolge im Test über Ereignisse steuern.** Erst auf einen tatsächlich ausstehenden Aufruf warten, dann Pause oder Auswahl auslösen und anschließend die alte Antwort freigeben. Alle neuen ausgehenden Play-, Resume- und Seek-Befehle nach diesem Punkt prüfen. Einen bereits abgesandten Befehl nicht mit einem nach der Benutzeraktion neu gesendeten Befehl verwechseln.
4. **Nachweis mit dem nächsten Commit liefern.** R1, R2 und Gegenprobe vor und nach der Korrektur; vorhandene M2.1- und Reload-Tests; CI für diesen Codecommit mit Prüfergebnissen und signiertem Arbeitsbranch-Artefakt. M2.2 und die Android-Transportsteuerung bis zum Abschluss dieser Nachprüfung getrennt lassen.

Für die kleine Nacharbeit gibt es noch keinen aus den Zusatzproben ableitbaren neuen Gerätetest. Der ursprüngliche Gerätehänger bleibt ein eigener Befund; native Wiedergabe, Gain-Anwendung und Logexport bleiben außerhalb dieses Diffs.

## Anhang: eigenständig ausgeführte Zusatzproben

Die folgende Prüfdatei lief außerhalb des Repositorys gegen das unveränderte `:api-spotify` einschließlich Claudes `ScriptedBridgeHost`. Benötigt Zugriff auf die internen Testschnittstellen, etwa im vorhandenen JVM-Testquellensatz, sowie `kotlinx-coroutines-test-jvm` passend zur aufgelösten Coroutines-Version. Alternativ dieselben Folgen mit dem bestehenden ereignisgesteuerten Testaufbau abdecken.

```kotlin
import app.myspoti.spotify.SpotifyPlayer
import app.myspoti.spotify.bridge.AdBlock
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.CapturedCall
import app.myspoti.spotify.bridge.PageAuth
import app.myspoti.spotify.bridge.PageDevice
import app.myspoti.spotify.bridge.ScriptedBridgeHost
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class M2ReviewProbes {
    private class Rig(private val test: TestScope) {
        val host = ScriptedBridgeHost()
        val bridge = Bridge(host, test.backgroundScope)
        val player = SpotifyPlayer(bridge, test.backgroundScope)
        init {
            setOf("media.state","bridge.version","volume.get","player.state","player.queue","device.activeNow",
                "player.audioInfo","drm.probe","player.preloadNext","diag.gain","ads.off","media.rise","media.ramp",
                "media.volume","media.fade","media.mute","media.normalize","media.boost","media.crossfade","media.ending")
                .forEach { host.answer(it) }
            test.runCurrent()
        }
        suspend fun state(track: String, paused: Boolean, pos: Long) {
            host.post("""{"type":"state","state":{"track":{"kind":"track","uri":"spotify:track:$track","uid":"u$track","name":"$track","durationMs":200000},"contextUri":"spotify:playlist:C","isPaused":$paused,"isBuffering":false,"positionMs":$pos,"timestamp":${System.currentTimeMillis()},"restrictions":{}}}""")
            test.runCurrent()
            assertEquals("spotify:track:$track",player.state.value?.item?.uri)
            assertEquals(paused,player.state.value?.isPaused)
            assertEquals(pos,player.state.value?.positionMs)
        }
        private suspend fun ready() {
            host.post("""{"type":"ready","version":1}""")
            test.runCurrent()
            assertTrue(bridge.ready.value)
        }
        suspend fun restorePausedA(newPosition: Long): CapturedCall {
            PageDevice.joined()
            ready()
            state("A",false,98_000)
            bridge.pageGone()
            test.runCurrent()
            ready()
            PageDevice.joined()
            test.runCurrent()
            state("A",true,newPosition)
            tick(101)
            return host.calls.single { it.method == "player.resume" }
        }
        fun tick(ms: Long) { test.advanceTimeBy(ms); test.runCurrent() }
        fun mutations(from: Int = 0) = host.calls.drop(from).filter {
            it.method in setOf("player.resume","player.play","player.seek","player.next","player.previous","queue.skipTo")
        }.map { "${it.method} ${it.args}" }
    }

    private fun resetShared() {
        PageDevice.resetForTests()
        PageAuth.pageGone()
        AdBlock.ahead.value = null
    }

    @Test fun cancelledResumeDoesNotSendRestoreSeekAfterPause() = runTest {
        resetShared()
        try {
            val r = Rig(this)
            val pending = r.restorePausedA(newPosition = 0)
            r.host.fail(pending,"No such device.")
            runCurrent()
            r.player.pause()
            runCurrent()
            val mark = r.host.calls.size
            r.tick(701)
            assertEquals(emptyList<String>(),r.mutations(mark),"The restore must stop after pause; trace: ${r.mutations()}")
        } finally { resetShared() }
    }

    @Test fun oldRestoreCannotReplayAAfterPickWhileResumeWasPending() = runTest {
        resetShared()
        try {
            val r = Rig(this)
            val pending = r.restorePausedA(newPosition = 98_000)
            val picking = backgroundScope.async { r.player.play("spotify:playlist:C",trackUri="spotify:track:P") }
            runCurrent()
            val user = r.host.calls.single { it.method == "player.play" }
            assertEquals("spotify:track:P",user.args["trackUri"]?.toString()?.trim('"'))
            r.host.succeed(user)
            runCurrent()
            picking.await()
            r.state("P",false,0)
            val mark = r.host.calls.size
            r.host.succeed(pending)
            runCurrent()
            r.tick(SpotifyPlayer.RESTORE_CHECK_MS + SpotifyPlayer.RESTORE_GAP_MS + 1_000)
            assertEquals(emptyList<String>(),r.mutations(mark),"The user pick must survive a late old ack; trace: ${r.mutations()}")
        } finally { resetShared() }
    }

    @Test fun positiveControlRestoresPositionWhenNoNewerPauseExists() = runTest {
        resetShared()
        try {
            val r = Rig(this)
            val pending = r.restorePausedA(newPosition = 0)
            r.host.succeed(pending)
            runCurrent()
            val seek = r.host.calls.single { it.method == "player.seek" }
            assertTrue((seek.args["positionMs"]?.toString()?.toLong() ?: 0) >= 98_000)
            r.host.succeed(seek)
            r.state("A",false,98_000)
            r.tick(SpotifyPlayer.RESTORE_CHECK_MS + SpotifyPlayer.RESTORE_GAP_MS + 1_000)
            assertEquals(2,r.mutations().size)
        } finally { resetShared() }
    }
}
```
