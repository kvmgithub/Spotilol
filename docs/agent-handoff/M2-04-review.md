# MySpotilol – Nachprüfung M2.1

Stand: 14. November 2026. Antwort auf `docs/agent-handoff/M2-03-result.md`.

**R1, R1b, R2 und R3 sind in den geprüften Abläufen behoben. M2.1 noch nicht integrieren: Nach einem vorübergehenden Fehler werden überholte Befehle der Wiederherstellung weiterhin neu gesendet.** Vier zusätzlich ausgeführte Tests belegen denselben offenen Fehler an dieser Stelle (R4). Die ursprüngliche Geräteursache bleibt offen.

## Geprüfter Stand

| Bezug | Commit |
| --- | --- |
| Identischer Testaufbau vor der Produktionskorrektur | `9bf3e49c5cf241d632007d1dab844fe168e81c41` |
| Produktionskorrektur und geprüfte APK | `d4c598dff5f6ba62f8655f2841f756d78cfd173d` |
| Geprüfte Spitze von `work/player-recovery-tests` | `6ae5328461ce9e8616b397eca1de7cedae962200`; nach dem Codecommit nur Claudes Ergebnisbericht |
| Produktbranch `myspoti-ui` beim Abruf | `63bff9587c7f3de2a258cd8b8278b0c6e64d9994`; M2.1 nicht integriert |

[Diff der Produktionskorrektur](https://github.com/kvmgithub/Spotilol/compare/9bf3e49c5cf241d632007d1dab844fe168e81c41...d4c598dff5f6ba62f8655f2841f756d78cfd173d). Der Vergleich enthält ausschließlich Änderungen an `SpotifyPlayer.kt`. Die Testdateien sind in beiden verglichenen Ständen identisch. Eigene Prüfungen liefen auf abgeglichenen Quellkopien; im Arbeitsbranch wird nur dieses Markdown abgelegt. Claude implementiert, HK entscheidet über Integration und Release.

## R1 bis R3: geprüft und geschlossen

| Befund | Beobachtung am unveränderten Ausgangscode | Ergebnis der Korrektur |
| --- | --- | --- |
| R1 und R1b: verspätete Erfolgsantwort nach neuer Titelwahl | Nach der Wahl von P wird A in einem weiteren Wiederherstellungsversuch erneut angefordert. Betrifft den Resume- und den Play-Zweig. | Die Wiederherstellung behält die Kennung ihres eigenen Plays und übernimmt nach der Antwort keine neuere Benutzerauswahl als eigene Kennung. Beide Fälle bestehen. |
| R2: verworfene Fortsetzung löst Seek aus | `resume` wird nach einer Pause nicht wiederholt, aber die alte Position wird noch gesetzt. | Der verworfene Befehl liefert intern `false`. Die Wiederherstellung endet vor dem Seek. Die Gegenprobe mit notwendiger Positionskorrektur besteht. |
| R3: Zustand der alten Seite ausgewertet | Der Zähler wird vor Übernahme des neuen Zustands erhöht. Bei künstlich verzögerter Verarbeitung sieht die Wiederherstellung noch „A spielt“ und sendet nichts. | Der Zähler wird nach der Zustandsverarbeitung erhöht. Im selben Test wird erwartungsgemäß A angefordert. |

Die lokalen Prüfungen reproduzieren die vier roten Tests mit identischem Testcode; alle vier bestehen im Korrekturstand. Die beiden ursprünglichen Zusatzproben für R1 und R2 bestehen dort ebenfalls. R3 belegt einen Ablauf im JVM-Test. Der Android-Host nutzt den Hauptthread für die Player-Coroutines; daraus und aus dem künstlich verlangsamten Log-Aufruf ergibt sich keine Aussage über die Häufigkeit auf dem Telefon.

## R4 – Interne Wiederholung sendet überholte Befehle erneut

**Reproduziert auf `d4c598d` und `9bf3e49`. Die Lücke bestand bereits vor dieser Nacharbeit.**

Die Wiederherstellung prüft ihre Gültigkeit vor einem äußeren Versuch und nach Abschluss von `resumePage()`. Ein vorübergehender Fehler wird aber **innerhalb** von `command()` behandelt. Dort folgen auf die Verzögerung und das mögliche Warten auf die Seitenbereitschaft ein neuer Aufruf. Die vorhandene letzte Prüfung verwirft nur `player.resume` nach einer Pause. Eine neuere Titelauswahl und die anderen Befehle der Wiederherstellung werden an dieser Stelle nicht berücksichtigt.

Alle vier Proben lassen zuerst einen konkreten Wiederherstellungsbefehl unbeantwortet. Danach folgt die Benutzeraktion. Erst dann antwortet die Bridge auf den alten Befehl mit `No such device.` und die Probe lässt die erste Wiederholungsfrist verstreichen. A und P sind synthetische Titel.

| Alter Befehl | Neuere Benutzeraktion | Tatsächlich neu gesendeter Befehl danach |
| --- | --- | --- |
| Wiederherstellung von A per Play | Benutzer pausiert, während die Seite bereits pausiert meldet | Erneut `player.play(A)` |
| Wiederherstellung von A per Play | Benutzer wählt P, Seite meldet P als spielend | Erneut `player.play(A)` mit der alten Position |
| Fortsetzen von A | Benutzer wählt P, Seite meldet P als spielend | Erneut `player.resume()` aus dem alten Ablauf |
| Positionskorrektur für A | Benutzer wählt P, Seite meldet P als spielend | Erneut `player.seek` auf die alte Position von A, ungefähr 98 s |

**Positiver Gegenfall:** Ohne neuere Benutzeraktion wiederholt die Wiederherstellung den vorübergehend abgelehnten Play-Befehl mit denselben Argumenten genau einmal. Sobald der Test die Wiedergabe von A meldet, folgt kein weiterer Befehl.

Der Befund betrifft **neu gesendete Befehle**. Die Proben erwarten nicht, dass ein schon abgesandter Befehl rückgängig gemacht wird. Sie belegen auch nicht, wie der echte Webplayer einen solchen erneuten Resume oder Seek auf dem Gerät ausführt.

Fundstellen: [Gültigkeitsprüfung der Wiederherstellung](https://github.com/kvmgithub/Spotilol/blob/d4c598dff5f6ba62f8655f2841f756d78cfd173d/base/api-spotify/src/main/kotlin/app/myspoti/spotify/SpotifyPlayer.kt#L697), [Play-Aufruf der Wiederherstellung](https://github.com/kvmgithub/Spotilol/blob/d4c598dff5f6ba62f8655f2841f756d78cfd173d/base/api-spotify/src/main/kotlin/app/myspoti/spotify/SpotifyPlayer.kt#L727), [interne Befehlswiederholung](https://github.com/kvmgithub/Spotilol/blob/d4c598dff5f6ba62f8655f2841f756d78cfd173d/base/api-spotify/src/main/kotlin/app/myspoti/spotify/SpotifyPlayer.kt#L2035), [Positionskorrektur](https://github.com/kvmgithub/Spotilol/blob/d4c598dff5f6ba62f8655f2841f756d78cfd173d/base/api-spotify/src/main/kotlin/app/myspoti/spotify/SpotifyPlayer.kt#L719).

## Auftrag für Claude

1. **Die vier R4-Proben als Regressionstests übernehmen.** Den Stand `d4c598d` als roten Ausgangspunkt verwenden. Zusätzlich den Fall ohne neuere Aktion erhalten: Der erste vorübergehende Fehler bleibt behebbar, dieselben Argumente werden erneut gesendet.
2. **Die Gültigkeit der konkreten Wiederherstellung bis zum tatsächlichen Senden prüfen.** Ihre Entscheidung muss auch innerhalb der Befehlswiederholung gelten: nach der Wartezeit und einer gegebenenfalls erneut abgewarteten Seitenbereitschaft, unmittelbar vor dem nächsten `bridge.call`. Play, Resume und Seek aus diesem Ablauf verwenden dieselbe Gültigkeitsregel. Die Entscheidung darf inzwischen neu gesetztes `lastPlay` nicht als eigene Auswahl vereinnahmen.
3. **Einen verworfenen Befehl intern als verworfen weitergeben.** Keine Folgeaktion und kein weiterer Versuch der überholten Wiederherstellung. Keine pauschale Änderung an der Wiederholungslogik für andere Zwecke, den Zeitgrenzen oder der öffentlichen Player-Schnittstelle. Bestehende Pause-/Resume- und Positions-Gegenproben beibehalten.
4. **Die Reihenfolge an der ausstehenden Call-ID festmachen.** Variante mit Pause beziehungsweise Auswahl während der Verzögerung oder der Bereitschaftswartezeit abdecken. Nach gewollter Verwerfung prüfen, dass die Routine beendet ist; eine kurze Phase ohne neue Befehle allein reicht nicht. Vorher/Nachher-Läufe mit identischem Testcode und CI für den exakten Codecommit liefern.

M2.2 und die Android-Transportsteuerung `SongPlayer` bleiben getrennt. P4 bildet weiterhin keinen asynchronen Relay-Fehler über `PageDevice.commandLost()` nach. Zusätzliche Geräteversuche sind für die Reproduktion von R4 nicht erforderlich. Die Ursache des Hängers vom 13. November wird durch diese Befehlsproben nicht bestimmt.

## Nachweise

| Prüfung | Ergebnis |
| --- | --- |
| [CI vor der Korrektur, 43588947196](https://github.com/kvmgithub/Spotilol/actions/runs/43588947196) | Originalberichte: 473 Tests in 73 Suiten, exakt vier Fehlschläge (R1, R1b, R2, R3), keine Fehler oder übersprungenen Tests. |
| [CI nach der Korrektur, 43589542281](https://github.com/kvmgithub/Spotilol/actions/runs/43589542281) und [Wiederholung, 43590152430](https://github.com/kvmgithub/Spotilol/actions/runs/43590152430) | Die heruntergeladenen XML-Berichte bestätigen jeweils 473/473. Beide Läufe prüfen die APK erfolgreich und überspringen die Veröffentlichung. Zwei grüne Durchläufe erlauben keine verlässliche Aussage über die Häufigkeit seltener Wettläufe. |
| Unabhängiger JVM-Lauf vor der Korrektur | 14 neue und vorhandene Playerfälle in `PlayerRecoverySequenceTest`: zehn bestanden, vier erklärbare Fehlschläge wie oben. |
| Unabhängiger JVM-Lauf auf `d4c598d` | 36/36 in Player-Recovery, Bridge, Reload und Charakterisierung. Kompiliert mit Kotlin 2.4.0; kein eigener Android-Build. |
| Acht Zusatzproben, je Stand | `9bf3e49`: zwei bestanden, sechs Fehlschläge (R1, R2 und die vier R4-Varianten). `d4c598d`: vier bestanden, vier Fehlschläge (nur R4). |
| Weitere lokale Prüfungen | 23/23 Node-Tests, Release-Notes-Prüfung und `git diff --check` der Produktionskorrektur bestanden. Je 86 Dateien aus `:api` und `:api-spotify` bytegenau mit den Git-Blobs abgeglichen. |

Die heruntergeladene [APK aus dem ersten grünen Lauf](https://github.com/kvmgithub/Spotilol/actions/runs/43589542281/artifacts/14111594414) hat selbst berechnet SHA-256 `2e368168d3eb1624d65ee18de5f056d4d0e77652105cf8fc8351406015dbdcfc`. Zuordnung laut Build-Metadaten: `d4c598d`, `com.project.lol.fork.ui`, 1.4.0 / 1004000, ARM64. Die CI-Signierprüfung bestätigt den erwarteten Fingerabdruck `73cf309a74b1a107a1e1cbb9453806f7b0d8e803957ecd371cb51cfed0b21e9a`. Lokal wurde der APK-Hash erneut berechnet, keine separate Signaturprüfung ausgeführt.

Die 300-ms-Pause im Testaufbau gibt dem `ready`-Sammler Zeit, garantiert aber keine Zustellung auf beliebig stark ausgelasteten Rechnern. Die überprüften Abbruchmeldungen und konkret erwarteten Befehle verhindern in den neuen Fällen, dass ein gänzlich ausgebliebener Wiederherstellungsablauf als Erfolg zählt. Eine allgemeine Umstellung dieses Testaufbaus ist kein Teil von R4.

## Anhang: ausgeführte JVM-Zusatzproben

Ausgeführt gegen beide unveränderten Quellstände, mit Claudes `ScriptedBridgeHost`. Verwendet `kotlinx-coroutines-test-jvm:1.11.0` nur im separaten Prüfaufbau und Zugriff auf interne Testschnittstellen. Die Coroutine-Verzögerungen wurden gesteuert; die Produktionsuhr wurde nicht ersetzt. Bei Übernahme in das Repository können dieselben Folgen mit dem vorhandenen Testaufbau abgedeckt werden.

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
class M2FollowUpProbes {
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
        suspend fun restorePausedA(newPosition: Long) = restore("A", newPosition, "player.resume")

        suspend fun restore(name: String, newPosition: Long, method: String): CapturedCall {
            PageDevice.joined()
            ready()
            state("A",false,98_000)
            bridge.pageGone()
            test.runCurrent()
            ready()
            PageDevice.joined()
            test.runCurrent()
            state(name,true,newPosition)
            tick(101)
            return host.calls.single { it.method == method }
        }

        suspend fun pickP() {
            val picking = test.backgroundScope.async { player.play("spotify:playlist:C", trackUri="spotify:track:P") }
            test.runCurrent()
            val user = host.calls.last { it.method == "player.play" }
            assertEquals("spotify:track:P", user.args["trackUri"]?.toString()?.trim('"'))
            host.succeed(user)
            test.runCurrent()
            picking.await()
            state("P",false,0)
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
    @Test fun obsoleteRestorePlayIsNotRetriedAfterPause() = runTest {
        resetShared()
        try {
            val r = Rig(this)
            val old = r.restore("B",0,"player.play")
            assertEquals("spotify:track:A", old.args["trackUri"]?.toString()?.trim('"'))
            r.player.pause()
            runCurrent()
            val mark = r.host.calls.size
            r.host.fail(old,"No such device.")
            runCurrent()
            r.tick(701)
            assertEquals(emptyList<String>(),r.mutations(mark),"Stale restore play retransmitted after pause; trace: ${r.mutations()}")
        } finally { resetShared() }
    }

    @Test fun obsoleteRestorePlayIsNotRetriedAfterNewPick() = runTest {
        resetShared()
        try {
            val r = Rig(this)
            val old = r.restore("B",0,"player.play")
            r.pickP()
            val mark = r.host.calls.size
            r.host.fail(old,"No such device.")
            runCurrent()
            r.tick(701)
            assertEquals(emptyList<String>(),r.mutations(mark),"Stale restore play retransmitted after pick; trace: ${r.mutations()}")
        } finally { resetShared() }
    }

    @Test fun obsoleteRestoreResumeIsNotRetriedAfterNewPick() = runTest {
        resetShared()
        try {
            val r = Rig(this)
            val old = r.restorePausedA(98_000)
            r.pickP()
            val mark = r.host.calls.size
            r.host.fail(old,"No such device.")
            runCurrent()
            r.tick(701)
            assertEquals(emptyList<String>(),r.mutations(mark),"Stale restore resume retransmitted after pick; trace: ${r.mutations()}")
        } finally { resetShared() }
    }

    @Test fun validRestorePlayStillRetriesATransientFailure() = runTest {
        resetShared()
        try {
            val r=Rig(this)
            val old=r.restore("B",0,"player.play")
            r.host.fail(old,"No such device.")
            runCurrent()
            val mark=r.host.calls.size
            r.tick(701)
            val retried=r.host.calls.drop(mark).single { it.method=="player.play" }
            assertEquals(old.args, retried.args)
            r.host.succeed(retried)
            r.state("A",false,98_000)
            r.tick(SpotifyPlayer.RESTORE_CHECK_MS + SpotifyPlayer.RESTORE_GAP_MS + 1_000)
            assertEquals(2,r.mutations().size)
        } finally { resetShared() }
    }

    @Test fun obsoleteRestoreSeekIsNotRetriedAfterNewPick() = runTest {
        resetShared()
        try {
            val r = Rig(this)
            val resume = r.restorePausedA(0)
            r.host.succeed(resume)
            runCurrent()
            val old = r.host.calls.single { it.method == "player.seek" }
            assertTrue((old.args["positionMs"]?.toString()?.toLong() ?: 0) >= 98_000)
            r.pickP()
            val mark = r.host.calls.size
            r.host.fail(old, "No such device.")
            runCurrent()
            r.tick(701)
            assertEquals(emptyList<String>(), r.mutations(mark), "Stale restore seek retransmitted after pick; trace: ${r.mutations()}")
        } finally { resetShared() }
    }

}
```
