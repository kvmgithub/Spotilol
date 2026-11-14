# MySpotilol M1 – Auswertung Gerätetest 1

> **Berichtigt** durch `M1-13-reload-und-berichtigung.md` (D1–D4): Die App hat einen manuellen Reload (Einstellungen → App → „Player neu starten“); Gleichheit an/aus nur eingeschränkt verglichen; Ursache des Hängers offen; Zahlen sind Stichproben der erhaltenen Zeilen.


Stand: 13. November 2026. Auswertung der Protokolle von HKs erstem Gerätetest nach Review 4 (`M1-09-review-4.md`). Gerät: Google Pixel 9 Pro XL, Android 17 (SDK 37). Rollen unverändert; diese Auswertung ändert keinen Code.

**Ergebnis:** In dem Ausschnitt, den die Protokolle abdecken, arbeitet die Gain-Diagnose wie spezifiziert: Titelwechsel, Kontextwechsel Playlist → Album und Ab-/Anmeldung erscheinen korrekt; alle Fehler-, Verlust- und Verspätungszähler stehen auf 0; keine `gain-diag`-Zeile enthält Kennungen, Tokens oder URLs. Wiedergabe im Hintergrund und bei gesperrtem Bildschirm lief zwölf Minuten ohne Absturz. **Ein Mangel von M1 wurde nicht gefunden.** Drei Prüfschritte sind durch die Protokolle nicht belegt, weil der Logpuffer die frühen Minuten nicht mehr enthält: der Vergleich Diagnose aus/an, ein Reload ohne Abmeldung und das Abschalten (letzteres nur indirekt). Dafür genügt ein kurzer zweiter Durchgang (Abschnitt 6).

## 1. Grundlage

| Datei | Inhalt | Zeitraum (MEZ) |
| --- | --- | --- |
| App-Export „last 10 min“ (`attachment.txt`) | Myspoti-Zeilen der App | 20:26:35 – 20:28:45 |
| `MySpotilol_log_c2bd20e8ed61.txt` | Logcat des App-Prozesses (alle Puffer) | 20:13:25 – 20:29:32; Myspoti-Zeilen erst ab 20:26:58 |
| `System_log_32c51a5b41b1.txt` | Logcat des Systems | bis 20:29:41; Myspoti-Zeilen erst ab 20:26:58 |

- **Zeitbasis:** Die beiden Logcat-Dateien zeigen UTC, der App-Export Ortszeit (identische Zeilen, z. B. 19:27:05.058 UTC = 20:27:05.058 MEZ). Alle Zeiten hier in MEZ.
- **Abdeckung:** Der Logcat-Ringpuffer hatte die Zeilen des App-Prozesses vor 20:26:58 bereits verworfen. Für 20:13–20:26 liegen nur Systemereignisse vor (Installation, Aktivitäten, Bildschirm, Lautstärke, Benachrichtigungen). Ausgewertet wurden 40 `gain-diag`-Zeilen (23 aus dem Export, 17 aus dem Logcat, teils dieselben).
- **Installierter Stand:** Update von `com.project.lol.fork.ui` (Version 1.4.0 / 1004000) um 20:13:22 über den Paketinstaller, Start 20:13:24. Die Zeilen tragen Formatversion `v=3` und das Feld `dropped=`, das es erst ab Code-Commit `18c323f` gibt; danach gibt es keinen weiteren Code-Commit. Das Update wurde über die bereits installierte App eingespielt, also mit demselben Signierschlüssel. Die SHA-256 der installierten Datei lässt sich aus den Protokollen nicht ablesen; HK hält sie für den Testbericht fest (`9a1c1b75…7b9bab` laut Übergabe 4).

## 2. Ergebnis je Prüfschritt

| Schritt (Plan aus Review 4) | Beobachtung | Status |
| --- | --- | --- |
| Diagnose aus/an vergleichen | Kein Protokoll aus der Zeit. Lauf 2 begann laut `ms=` um 20:13:34, zehn Sekunden nach dem App-Start; der Anlass (Einschalten oder Kontowechsel) steht nicht mehr im Puffer. | nicht belegt |
| Titelwechsel | 20:26:36 „Weiter“: Seitenzeile `ev=track page=3 track=t14`, danach Antwortzeilen mit `answerCurrent=t15` bzw. `t14`. 20:27:05 Wechsel auf `t19` mit Seitenzeile und Antworten. | bestanden |
| Lautstärke | HK änderte die Android-Medienlautstärke mit den Tasten (Systemereignisse `volume_changed stream=3` um 20:14:19–22 und 20:26:52). Die Diagnose beobachtet die Lautstärke des Medienelements, nicht die Android-Lautstärke; `ev=volume` ist dabei nicht zu erwarten und erscheint nicht. | wie vorgesehen; Grenze siehe 4.e |
| Pause / Fortsetzen | 20:26:39 Pause, 20:26:40 weiter (Seitenzustand). Pause ist kein Diagnoseereignis; Wiedergabe lief weiter. 20:28:34 Fortsetzen nach Neuanmeldung mit Antwortzeilen. | bestanden (funktional) |
| Hintergrund und Sperrbildschirm | Bildschirm aus um 20:14:08 (Ein-/Aus-Taste), Aktivität gestoppt bis 20:26:33. In dieser Zeit laufende Wiedergabe: Medienbenachrichtigung bei jedem Titelwechsel aktualisiert (20:15:36 bis 20:26:16), normale CPU-Zeit des Prozesses, kein Absturz, kein ANR. Diagnosezeilen aus dieser Zeit sind nicht mehr im Puffer. | bestanden (funktional) |
| Gleicher Titel aus Playlist und Album | 20:27:15 Seitenzeile `ev=context page=3 track=t19 ctx=album`; die Kandidaten stammen aus Playlist-Antworten und tragen korrekt `here=other`. Die nächste Antwort (`ans=61`) kommt mit `ctx=album` und denselben Kandidaten `f27`/`f28`. | bestanden |
| Reload ohne Abmeldung | Im Protokoll nicht enthalten. `page=3` in Lauf 2 zeigt, dass die Seite in diesem Lauf vorher neu bewaffnet wurde, der Anlass ist aber nicht mehr sichtbar. | nicht belegt |
| Ab- und Anmeldung | 20:27:36 Startzeile `run=3 reason=account`, Anmeldeseite, Pause, Abmeldung der Geräte. 20:28:21 neue Seite bereit, 20:28:22 `ev=enable page=4`, Kennungen beginnen wieder bei `t1`/`f1`, `late=0`, `bad=0`. Keine Zeile mit Daten des alten Laufs. | bestanden |
| Abschalten | Letzte Diagnosezeile 20:28:37. Die nächste Track-Playback-Antwort um 20:29:06 (Status 200) erzeugte keine Zeile, obwohl bis dahin jede solche Antwort eine erzeugte. Das passt zu einem Abschalten zwischen 20:28:58 und 20:29:06. Ob HK dort abgeschaltet hat, sagt das Protokoll nicht. | indirekt; HK bestätigt |
| Export, Datenschutz der `gain-diag`-Zeilen | 40 Zeilen geprüft: keine `spotify:`-Kennung, keine Hex-Kennung ab 16 Zeichen, keine URL, kein Token, kein Cookie. Feldnamen nur aus der festen Liste. | bestanden |

Unabhängig vom Plan: In keiner Zeile ist `late`, `bad`, `dropped`, `merged`, `pageFaults`, `evicted`, `aliasEvicted`, `capped` oder `otherLists` größer 0. `pvSt=ok` und `cubic=true` in allen Seitenzeilen, die privaten Playerfelder sind in dieser Webplayer-Version also vorhanden. Keine Meldung „gain diagnostics: …“, keine rohe `diag`-Nachricht im Log.

## 3. Bestätigte Eigenschaften im Feld

- **Seiten- und Laufbindung:** Bei der Abmeldung liefen nach dem neuen Lauf noch zwei DELETE-Anfragen der alten Seite über das Relay; keine davon erzeugte eine Zeile oder einen `late`-Zähler. Die erste Registrierungsanfrage der neuen Seite (20:28:22.187, vor der Bewaffnung) trägt kein Ticket; die erste Zustandsantwort danach ist `ans=1`.
- **Anfragefolge:** `ans` zählt alle Relay-Anfragen der Seite, auch connect-state. Die Lücken (z. B. 55, 59) sind die Befehlsanfragen dazwischen; das entspricht der Implementierung.
- **Herkunft der Kandidaten:** Die Seitenzeile beim Skip zeigt die Kandidaten des neuen Titels aus einer früheren Antwort mit `role=queue`, die beim Kontextwechsel `here=other`.
- **Rate:** Höchstens eine bis zwei Zeilen je Sekunde, nie gedrosselt (`merged=0`), 67 Zeilen in Lauf 2 (`seq` 0–66) über rund 13,7 Minuten.

## 4. Beobachtungen für die Auswertung (keine Mängel von M1)

a. **Seitenzeile beim Titelwechsel ist eine Momentaufnahme des Übergangs.** Sie entsteht, während das Element noch pausiert bzw. puffert und der Pegel am Boden der Blende steht (`paused=true`, `level=0.0840` bzw. `0.0020`). Den Pegel nach dem Wiederanstieg hält keine Zeile fest: Der Anstieg läuft bei `routed=true` über den Audiograph, nicht über `el.volume`, und löst daher kein Ereignis aus. Für M2 fehlt damit eine Pegelmessung im eingeschwungenen Zustand. Das ist eine Frage des Messdesigns für M2, keine Abweichung vom M1-Auftrag.

b. **Die Seitenzeile kommt vor der Antwort mit den Kandidaten.** Beim Wechsel auf `t19` steht `gain=none`; die Kandidaten kommen eine Sekunde später in der Antwortzeile (`answerCurrent=t19`). Zuordnung für die Auswertung also über Lauf, Seite und Titelalias der Antwortzeilen.

c. **Beide Formatkandidaten haben in jedem beobachteten Fall denselben `gain_db`.** `mp4` (Format 10) und `mp4_dual` (Format 12) stimmen bei allen elf beobachteten Titeln überein. Dass die Diagnose nicht weiß, welche Datei spielt, spielt für diese Titel praktisch keine Rolle.

d. **Gleicher Titel, gleiche Werte über Kontext und Sitzung.** `t19` (Lauf 2) hat in Playlist und Album `−0,5182 dB`; nach der Neuanmeldung trägt derselbe Titel als `t1` (Lauf 3) denselben Wert, ebenso die beiden Albumtitel danach (`−0,9930`, `−1,6240`). Für diesen einen Titel unterscheidet Spotify Album- und Playlist-Kontext also nicht. Das ist ein Einzelfall, keine allgemeine Aussage.

e. **Android-Lautstärke ist nicht Teil der Diagnose.** `vol=` ist die Elementlautstärke (hier immer 1,0). Systemlautstärke und Lautsprecher-/Kopfhörerpfad gehen in die hörbare Lautheit ein, erscheinen aber nicht in den Zeilen.

**Erste Rohwerte (zwei Titel, keine Schlussfolgerung):**

| Titel | App-Normalisierung (Logzeile `normalization:`) | Lautheit im Cache (`loud=`) | Spotify `gain_db` |
| --- | --- | --- | --- |
| „Blackreach Rising“ (`t14`) | +1,9 dB | −11,889 | −3,3488 |
| „Warhammer 40,000: Space Marine 2 – Main Theme – Epic Version“ (`t19`, Lauf 3 `t1`) | +5,8 dB | −15,810 | −0,5182 |

Die App-Normalisierung rechnet in beiden Fällen `−10 − Lautheit`; Spotifys `gain_db` weicht davon um 5,2 bzw. 6,3 dB ab, also nicht um einen festen Betrag. Dazu war `boost=2.00` aktiv. Zwei Titel reichen für keine Aussage über die Normalisierung; sie zeigen nur, dass die Messung die Rohdaten für M2 liefert.

## 5. Nebenbefund außerhalb von M1

Andere, schon vor M1 vorhandene Logzeilen im App-Logcat enthalten Spotify-Kennungen: Titel- und Playlist-URIs (z. B. in `library.contains`, `liked check`, `track change`), Connect-Geräte-IDs und die Nutzerkennung samt Anzeigename (Antwort auf `UserAPI.getUser`). Das betrifft nicht die `gain-diag`-Zeilen und ist kein M1-Befund. Für die Weitergabe von Protokollen heißt es: Die vollständigen Logcat-Exporte nur an Prüfer geben und nicht veröffentlichen; ins Repository kommen sie deshalb nicht. Ob diese Logpfade gekürzt werden sollen, ist eine eigene Entscheidung außerhalb von M1.

## 6. Offen und Vorschlag für Durchgang 2

Offen sind nur Nachweise, kein Code:

1. Vergleich Diagnose aus/an.
2. Reload ohne Abmeldung. Die App bietet keinen manuellen Reload; er tritt auf, wenn die App die Seite selbst neu lädt (z. B. nach einer festgefahrenen Wiedergabe oder einem abgelaufenen Seitentoken nach Netzwechsel). Im Log: `page start https://open.spotify.com/…` und `page ready` ohne vorherige `sign-in page`, danach `ev=enable` mit neuem `page=` und gleichem `run=`. Tritt er im Test nicht auf, bleibt der Punkt durch die automatisierten Tests belegt (`GainDiagnosticsTest` 13 und 16, `RelayAnswersTest` 5).
3. Abschalten: HK bestätigt, ob er um etwa 20:29:00 abgeschaltet hat, oder wiederholt den Schritt.

Durchführung in einem kurzen Durchgang:

- Vorher in den Entwickleroptionen „Logger-Puffergrößen“ auf 16 MB stellen; der Standardpuffer hielt nur rund drei Minuten App-Zeilen.
- Diagnose aus: zwei Titelwechsel, eine Pause. Diagnose an: dieselben Schritte. Hörbar gleich? (HKs Urteil; aus dem Log nicht ablesbar.)
- Diagnose aus, danach einen Titelwechsel abwarten: keine neue `gain-diag`-Zeile.
- Gleich danach exportieren (App-Export und Logcat).

Hörbare Wiedergabe und Lautstärke kann nur HK beurteilen; die Protokolle zeigen dazu nur, dass die Wiedergabe ohne Fehler lief.
