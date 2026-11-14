# MySpotilol M1 – Reload-Nachweis und Berichtigung der Gerätetest-Auswertungen

Stand: 14. November 2026. Antwort auf „Review der beiden M1-Gerätetests“ (`M1-12-review-geraetetests.md`). Keine Codeänderung. Die dort genannte Datei „MySpotilol-M1-Abschluss-Reload-2026-11-14.md“ liegt mir nicht vor; Abschnitt 1 ist meine eigene Auswertung desselben Exports.

## 1. Reload bei eingeschalteter Diagnose (Gerätetest 3, 13.11.2026, MEZ)

Grundlage: App-Export „last 10 min“ von HK, 21:36:16–21:37:01, 16 `gain-diag`-Zeilen. Der Reload wurde über **Einstellungen → App → Player neu starten** ausgelöst, gleicher Prozess (PID 31601), keine Abmeldung.

| Zeit | Beobachtung |
| --- | --- |
| 21:36:42–21:36:49 | Lauf 2, Seite 2: Fortsetzen, zwei Skips (zu einem Sprung zusammengefasst), Seek. Antwort- und Seitenzeilen mit `page=2`. |
| 21:36:52.8 | Reload: Geräte der alten Seite abgemeldet, letzte Zeile der alten Seite `ev=track page=2 track=unknown` (Zustand der verschwindenden Seite). |
| 21:36:53.1–54.5 | Neue Seite startet und meldet Bereitschaft. |
| 21:36:54.541 | `ev=enable page=3`, **`run=2` unverändert**, `seq` läuft weiter (14), `pl=1`/`el=1`. |
| 21:36:54.7–21:37:01 | Antworten `page=3` (`ans=19`, 21, 23, 24); Seitenzeile `ev=track page=3 track=t2` mit Kandidaten aus einer Antwort der alten Seite, korrekt ausgewiesen als `(ans=15;page=2;…)`; Pause mit Antwortzeile. |

- Kein Kontowechsel-Lauf, `late=0`, `bad=0`, `dropped=0`. Zwei Antwortzeilen tragen `merged=1`: Die Drosselung hat je eine Zeile zusammengefasst.
- Die 16 Zeilen enthalten keine Kennungen, Tokens oder URLs.
- Ein Titelwechsel nach dem Reload ist im Export nicht enthalten; er endet mit der Pause um 21:37:00. Die Beobachtungen nach dem Reload (Antworten und Seitenzeile des laufenden Titels) gehören zur neuen Seite.

**Ergebnis:** Reload bei eingeschalteter Diagnose auf dem Gerät belegt: neue `page`, gleicher `run`, Herkunft der Kandidaten über den Reload erhalten. Damit ist der letzte offene Gerätepunkt geschlossen, mit der genannten Einschränkung, dass der Titelwechsel nach dem Reload fehlt.

Nebenbei im selben Export, außerhalb der `gain-diag`-Zeilen: Die bestehende Logzeile zu `UserAPI.getUser` enthält `displayName` und `accountId` im Klartext, `uri` und `username` sind ersetzt. Das ist die im Review genannte separate Exportlücke, weder M1-Regression noch in M1 behoben.

## 2. Berichtigungen zu `M1-10-geraetetest-1.md` und `M1-11-geraetetest-2.md`

Die beiden Dokumente bleiben als damalige Fassung stehen; ihre Kopfzeilen verweisen auf diese Berichtigung.

**D1 – Manueller Reload vorhanden.** Meine Aussage „Die App bietet keinen manuellen Reload“ (Gerätetest 1, Abschnitt 6) und „Ein Reload lässt sich nicht gezielt auslösen“ (Gerätetest 2, Abschnitt 5) war falsch. **Einstellungen → App → „Player neu starten“** ruft `MyspotiRuntime.host.reload()` auf. Meine Suche nach „neu laden“ in den Ressourcen hat den Eintrag „neu starten“ übersehen.

**D2 – Schalter und Wiedergabegleichheit getrennt.** Richtige Formulierung für Gerätetest 2: *Ein-/Ausschalten im beschriebenen Geräteablauf bestätigt; identischer Ablauf an/aus auf dem Gerät nicht vollständig verglichen.* Die automatisierten Vergleiche von Befehlen, Argumenten, Reihenfolge und Relay-Antworten bleiben davon unberührt.

**D3 – Ursache des Hängers offen.** Richtige Einordnung: *Wiedergabehänger bei ausgeschalteter Diagnose; Zusammenhang mit M1 ungeklärt, durch die vorliegenden Angaben nicht belegt.* Die Überschrift „außerhalb von M1“ bezeichnet nur die getrennte Bearbeitung. Auch bei ausgeschalteter Diagnose laufen `RelayAnswers` und das erweiterte Wrapper-Feld mit. HTTP 200, `ready=0` und der hängende Befehl beschreiben den Fehlerzustand, nicht seine Ursache. Der Fall geht als Vorlage in M2.1.

**D4 – Reichweite der Zahlen.**

| Bisher | Belastbare Lesart |
| --- | --- |
| 40 Diagnosezeilen (Test 1) | 23 Export- und 17 Logcat-Zeilen mit Überschneidung, keine 40 unabhängigen Beobachtungen |
| 14 Zeilen (Test 2) | kleine erhaltene Stichprobe, frühere Zeilen fehlen |
| alle Zähler 0 | in den erhaltenen Zeilen kein gezählter Fehler oder Verlust; kein Vollständigkeitsnachweis |
| `seq` 0–66 in 13,7 min | keine Prüfung der verlorenen Zeitfenster; die Grenze ist separat getestet |
| Hintergrund/Sperre bestanden | Systemereignisse und Benachrichtigungen stützen fortgesetzte Playeraktivität; Hörbarkeit nur durch HK |
| DELETE der alten Seite ohne Zeile | kein gezielter Nachweis der Abweisung alter Playback-Antworten; die prüfen die automatisierten Tests |
| `page=2` nach zwei Reloads bei aus | zweite vergebene Aktivierungskennung, kein Zähler der Reloads |
| „spielt hörbar“ (Test 2, Abschnitt 4, Schritt 4) | Player-Logmeldung „a song plays after the reload“; hörbar nur bei eigener Hörbeobachtung |

Zur Normalisierung (Review, Abschnitt 5): Die App rechnet `clamp(−10 − loudness, −12, +6)` dB; die von mir vermutete Obergrenze ist damit im Code bestätigt. `level` ist eine Stellgröße (`el.volume × boost.out.gain` bei geroutetem Element), keine Lautheitsmessung.

## 3. Stand M1

| Bereich | Stand |
| --- | --- |
| Code | `18c323f`, technisch freigegeben (Review 4), keine weitere Korrektur |
| Gerät | Skip, Kontextwechsel, Ab-/Anmeldung, natürlicher Wechsel, Pause/Fortsetzen, Schalter, Reload bei an: belegt; Gleichheit an/aus nur eingeschränkt verglichen; Hörbarkeit: HK |
| Offen außerhalb M1 | Wiedergabehänger um Werbung/Reload (M2.1), Exportlücke `displayName`/`accountId` |
| Integration | HKs Entscheidung |
