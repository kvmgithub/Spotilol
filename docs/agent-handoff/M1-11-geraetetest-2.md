# MySpotilol M1 – Auswertung Gerätetest 2

> **Berichtigt** durch `M1-13-reload-und-berichtigung.md` (D1–D4): Die App hat einen manuellen Reload (Einstellungen → App → „Player neu starten“); Gleichheit an/aus nur eingeschränkt verglichen; Ursache des Hängers offen; Zahlen sind Stichproben der erhaltenen Zeilen.


Stand: 13. November 2026. Zweiter Durchgang von HK nach `M1-10-geraetetest-1.md`, Prozess neu gestartet um 20:58:41 MEZ, gleiche APK (Formatversion `v=3`, Feld `dropped=`). Protokolle: App-Export (ab 21:01:57), App-Logcat und System-Logcat (UTC; Myspoti-Zeilen ab 21:02:11 MEZ). Keine Codeänderung.

**Ergebnis:** Durchgang 2 schließt drei der vier offenen Punkte: Abschalten, Diagnose aus/an und ein natürlicher Titelwechsel mit Überblendung sind jetzt belegt. Alle 14 `gain-diag`-Zeilen ohne Kennungen, alle Zähler 0. Offen bleibt nur ein Reload, während die Diagnose an ist: Beide Reloads dieses Durchgangs fielen in die Aus-Phase. In der Aus-Phase gab es Wiedergabeprobleme rund um eine Werbung; sie traten bei ausgeschalteter Diagnose auf und liegen in Code, den M1 nicht verändert hat (Abschnitt 4).

## 1. Ablauf (MEZ, aus den Protokollen rekonstruiert)

| Zeit | Geschehen | Diagnose |
| --- | --- | --- |
| 20:58:41 | App-Start | aus (Schalterstand vom Ende des ersten Tests) |
| 21:00:56 | Diagnose eingeschaltet: Lauf 1 beginnt (aus `ms=` der späteren Zeilen; die ersten zwölf Zeilen sind nicht mehr im Puffer) | an |
| 21:01:57 | Titel endet natürlich, Überblendung 12 s („the song ended“) | Seitenzeile `ev=track page=1 track=t7`, Antworten |
| 21:02:03 – 21:02:11 | Pause, Fortsetzen, Pause | Antwortzeilen bis 21:02:11 (`seq=18`) |
| zwischen 21:02:11 und 21:03:00 | Diagnose ausgeschaltet (letzte Bedienung der App um 21:02:19) | aus |
| 21:03:00 – 21:04:01 | Fortsetzen, Skip, Skip auf eine fällige Werbung, Reload wegen hängender Werbung, Skip, Zurück, Pause, Wiedergabe hängt, zweiter Reload | keine Zeile |
| 21:04:04 | Diagnose für den Export wieder eingeschaltet (Bedienung um 21:04:04.147) | Lauf 2: `ev=start reason=on`, `ev=enable page=2` |

Das Wiedereinschalten vor dem Export ist nötig, weil derselbe Schalter den Exporteintrag einblendet. Es stört die Auswertung nicht: Die Startzeile `reason=on` markiert das Ende der Aus-Phase eindeutig.

## 2. Ergebnis je offenem Punkt aus Gerätetest 1

| Punkt | Beobachtung | Status |
| --- | --- | --- |
| Abschalten | Nach dem Ausschalten erzeugen weder Track-Playback-Antworten (z. B. 21:03:00, 21:03:02, 21:03:24, alle Status 200) noch Titelwechsel oder die beiden Reloads eine Zeile. Erst das Einschalten um 21:04:04 startet einen neuen Lauf. | bestanden |
| Diagnose aus/an | An-Phase: natürlicher Wechsel, Pause, Fortsetzen, Pause ohne Auffälligkeit. Aus-Phase: keine Zeilen. Die Probleme in der Aus-Phase (Abschnitt 4) traten bei ausgeschalteter Diagnose auf. Die beiden Phasen enthielten nicht dieselben Schritte, ein Eins-zu-eins-Vergleich ist daher nicht möglich. Ob der Klang gleich war, kann nur HK beurteilen. | bestanden, mit dieser Einschränkung |
| Natürlicher Titelwechsel mit Überblendung (neu) | Die Seitenzeile entsteht zu Beginn der Überblendung, 21:01:57.145. Sie zeigt den neuen Titel `t7` mit Kandidaten aus einer früheren Antwort (`role=queue`, `here=same`). Das Element ist dasselbe (`el=1`, Momentaufnahme `paused=true`, `level=1.0000`). Die folgenden Antworten melden `answerCurrent=t7`. Eine Antwort, die noch vor dem Wechsel angefragt wurde, meldet korrekt `answerCurrent=t8`. | bestanden |
| Reload ohne Abmeldung | Zwei Reloads um 21:03:09 und 21:04:02, beide bei ausgeschalteter Diagnose: keine Bewaffnung, keine Zeile, wie vorgesehen. Die Seitenkennung läuft danach weiter (`page=2`, nicht wieder 1); `pl=1`/`el=1` beginnen auf der neuen Seite wieder bei 1. Ein Reload bei eingeschalteter Diagnose kam nicht vor. | teilweise; Reload bei an weiter nur durch die automatisierten Tests belegt |

Zusätzlich bestätigt:

- **Werbung:** Antworten mit einer Werbung in der Warteschlange erscheinen mit `ads=1`. Die Diagnose liest die Fassung, die nach dem Werbefilter an die Seite ging; die Logzeilen des Filters erscheinen wie bisher, verschoben nach `RelayAnswers`: „ad left out“, „ad kept in the queue“, „ad ahead“.
- **Gain 0,0:** Ein Titel hat `gain_db` genau `0.0000` und wird als Zahl ausgegeben, nicht als `absent` oder `null`.
- **Datenschutz:** 14 Zeilen geprüft, keine `spotify:`-Kennung, keine Hex-Kennung ab 16 Zeichen, keine URL, kein Token, kein Cookie.
- **Zähler:** `late`, `bad`, `dropped`, `merged`, `pageFaults`, `evicted`, `aliasEvicted`, `capped`, `otherLists` überall 0.

## 3. Weitere Rohwerte (für M2, keine Schlussfolgerung)

| Titel | App-Normalisierung | Cache-Lautheit | Spotify `gain_db` |
| --- | --- | --- | --- |
| „The Imperial March (Darth Vader's Theme)“ (`t7`) | +3,8 dB | −13,82 | 0,0000 |
| „Warhammer 40,000 … Epic Version“ (`t8`) | (+5,8 dB, Test 1) | (−15,81) | −0,5182, gleich wie in Test 1, dritte Sitzung |

Die Differenz zwischen App-Normalisierung und Spotify `gain_db` liegt damit bei 3,8, 5,2 und 6,3 dB über drei Titel. Aus der Aus-Phase stammt ein weiterer Hinweis: Die App deckelt ihre eigene Anhebung offenbar bei +6 dB („Darth Revan Theme x Nazgul Theme“: Lautheit −17,3, Anhebung 6,0 statt 7,3 dB). Beide Formatkandidaten tragen wieder jeweils denselben Wert.

## 4. Nebenbefund: Wiedergabeprobleme in der Aus-Phase (außerhalb von M1)

Ablauf ab 21:03:02, Diagnose aus:

1. Ein Skip wird wegen einer fälligen Werbung auf einen späteren Titel umgeleitet („next with an ad due“). Die Seite zeigt trotzdem die Werbung („ad break started“), die App schaltet stumm.
2. 21:03:09 „ad stuck on —: reloading the player“, Reload. Nach dem Reload meldet das erste Abspielen DEVICE_NOT_FOUND und wird wiederholt.
3. „Disposal Unit – Imperium Mix“ puffert, `player.resume` hängt 11,9 s („transport stuck“).
4. Skip auf den nächsten Titel spielt hörbar („a song plays after the reload“).
5. Zurück auf „Disposal Unit“ puffert erneut; eine Pause übernimmt die Seite nicht, die App wiederholt sie zweimal.
6. Zweimal „player stalled (buffering)“ mit `ready=0`, dann zweiter Reload um 21:04:02, danach setzt der Titel bei 0 s neu an.

Einordnung:

- Die Diagnose war die ganze Zeit aus: keine Bewaffnung, keine Tickets (`window.__mspGainTag` = 0), keine Zeilen.
- Von M1 laufen auch bei ausgeschalteter Diagnose zwei Stellen mit. Erstens der in `RelayAnswers` verschobene letzte Antwortschritt; seine Logzeilen erscheinen hier vollständig und in der gewohnten Reihenfolge. Zweitens das Feld `gain` (hier 0) in der Nachricht des Fetch-Wrappers an den Host; an Spotify geht davon nichts.
- Alle Track-Playback-Antworten in dieser Phase hatten Status 200. Das Hängen betrifft das Medienelement der Seite (`ready=0`, puffert) und Befehle an die Seite, also Code des Players, den M1 nicht verändert hat.
- Nach dem Logbild ist das kein M1-Fehler. Beweisen lässt sich das aus diesen Protokollen nicht. Klären würde es dieselbe Lage (Skip auf eine fällige Werbung) mit der Release-APK 1.4.0 ohne M1. Kennt HK dieses Verhalten schon aus 1.4.0, gehört es in einen eigenen Fehlerbericht.

## 5. Stand der Geräteabnahme

| Prüfschritt | Gerätetest 1 | Gerätetest 2 |
| --- | --- | --- |
| Titelwechsel per Skip | bestanden | – (in der An-Phase nicht im Puffer) |
| Natürlicher Titelwechsel mit Überblendung | – | bestanden |
| Pause/Fortsetzen | bestanden | bestanden |
| Hintergrund, Sperrbildschirm | bestanden | – |
| Playlist → Album | bestanden | – |
| Ab-/Anmeldung | bestanden | – |
| Reload ohne Abmeldung | – | nur bei ausgeschalteter Diagnose |
| Diagnose aus/an | – | bestanden, mit Einschränkung |
| Abschalten | indirekt | bestanden |
| Datenschutz der `gain-diag`-Zeilen | bestanden | bestanden |

Für die Abnahme bleibt damit nur der Reload bei eingeschalteter Diagnose offen; die automatisierten Tests dazu sind `GainDiagnosticsTest` 13 und 16 sowie `RelayAnswersTest` 5. Ob das genügt oder ein dritter Durchgang mit eingeschalteter Diagnose bis zu einem Reload folgen soll, entscheidet Chatti bzw. HK. Ein Reload lässt sich nicht gezielt auslösen. In diesem Durchgang entstand er durch einen Skip auf eine fällige Werbung; bei einem kostenlosen Konto ist das der wahrscheinlichste Weg.

Hinweis zum Logpuffer: Auch in diesem Durchgang hielt der Logcat nur rund zwei Minuten an App-Zeilen. Falls ein dritter Durchgang folgt, vorher in den Entwickleroptionen „Logger-Puffergrößen“ auf 16 MB stellen.
