# Spotilol UI – Änderungen

Release-Notizen der Spotilol-UI-Pre-Releases (`ui-v…`), neueste zuerst. Jeder Abschnitt ist der Text des GitHub-Releases gleicher Version; die CI ergänzt darunter Commit, Paket und Prüfhinweis.

Für das nächste Release kommt oben ein Abschnitt `## Unveröffentlicht` hinzu. Ein Commit mit `[publish]` veröffentlicht genau diesen Text und benennt den Abschnitt danach in die vergebene Version um. Ohne diesen Abschnitt bricht die Veröffentlichung ab.

## 0.2.1010 – 2026-10-20

Myspoti 2.0.0-beta.9 übernommen, soweit es zum Spotilol-Umfang passt.

### Werbung und Wiedererkennbarkeit
- Die Werbe-Manager der Spotify-Seite werden zur Laufzeit abgeschaltet, auch erneut bei jeder Werbeunterbrechung.
- Die Geräteanmeldung geht unverändert an Spotify. Bisher wurden die Werbeformate daraus entfernt; das stand im Verdacht, Free-Konten mit `402 COMMAND_FAILED` sperren zu lassen.
- Der Header `X-Requested-With` (Paketname der App) wird für Anfragen der Seite abgeschaltet, sofern die WebView das unterstützt.
- Die Browser-Kennung folgt der installierten WebView-Version statt fest Chrome 155. Einmalig kann deshalb eine neue Anmeldung nötig sein.
- Die Client-Gerätekennung bleibt über Neustarts erhalten.
- Weitergeleitete Wiedergabebefehle tragen dieselben Browser-Header wie die Seite. Cookies gehen nur mit, wenn Spotifys eigene Anfrage sie anfordert; `Set-Cookie` wird weiterhin verworfen.

### Stabilität
- Stürzt der Renderer der WebView ab, baut die App die Seite neu auf, statt stumm zu bleiben.
- Die Anmeldung wartet auf einen echten Sitzungstoken und hängt nicht mehr endlos.
- Schnelle Sprünge während Werbung oder Neuladen landen auf dem richtigen Titel; Podcast-Episoden bleiben nicht mehr am Ende hängen.

### Neu
- Crossfade (Einstellungen → Wiedergabe: aus, 3–12 s).
- „Zuletzt gehört“ im Profilmenü, gruppiert wie in der offiziellen App.
- Podcast- und Episodenseiten, eigener Podcast-Player (±15 s, Geschwindigkeit, als gespielt markieren).
- Titel aus eigenen Playlists entfernen oder zu einer anderen hinzufügen; Optionsmenü auf Künstlerseiten.
- Cover und Profilbilder, die bisher fehlten, werden geladen; kommende Liedtextzeilen sind auf dunklen Karten lesbar; Navigationsleiste ohne hellen Streifen.

### Bewusst nicht übernommen
- Podcast-Downloads, Update-Prüfung, Datei-Export des Diagnoseberichts (die Logansicht deckt das ab), abgeschalteter Audio-Proxy.
- Telefonname in Spotifys Geräteliste: Die App erscheint weiter als „Web Player (Chrome)“.

## 0.1.1009 – 2026-10-19

### Behoben
- Bei Netzausfall (WLAN-/Mobilfunkwechsel, VPN-Neuverbindung, DNS-Ausfall) lud die Stall-Erkennung die Seite alle paar Sekunden neu. Jetzt wartet sie, bis Spotify wieder erreichbar ist, und versucht es dann einmal.
- Bei einem Netzwechsel verwirft die App offene Verbindungen, damit Anfragen nicht bis zum Zeitlimit auf der alten Route hängen.

### Diagnose
- Neue Logzeilen: `network lost`, `network changed`, `offline: stall recovery waits…`, `network back…`.

## 0.1.1008 – 2026-10-19

### Behoben
- Eine Pause, die Spotify während des Pufferns verworfen hat, wird bis zu zweimal erneut gesendet.
- GrapheneOS (Secure App Spawning): Geht die erste Seitenladung verloren, lädt die App sofort neu statt nach 4 Sekunden.

### Diagnose
- Blockierte Telemetrie erscheint einmal pro Host und Pfad als `telemetry blocked: …`.

## 0.1.1007 – 2026-10-19

### Geändert
- Der schwebende Log-Punkt ist entfernt. Die geschwärzten Logs der letzten 10 Minuten öffnen sich unter Einstellungen → App → Diagnoseprotokoll, mit Kopieren und Teilen.
- Die letzten Einträge der Einstellungen lassen sich über Miniplayer und Navigationsleiste hinausscrollen.
- Texte nennen die App „Spotilol UI“ und erwähnen keine Downloads oder Musikvideos mehr.

## 0.1.1006 – 2026-10-19

### Behoben
- Profilwechsel: Die Seite des bisherigen Kontos wird vor dem Wiederherstellen der Cookies geschlossen, damit sie diese nicht zurückschreiben kann.

## 0.1.1005 – 2026-10-19

### Entfernt
- QR-Scanner für Jam-Einladungen (Google Play Services mit ML Kit und Googles Datatransport-Protokollierung). Einem Jam beitreten geht weiter per Einladungslink.

## 0.1.1004 – 2026-10-19

Erste Testversion: die native Oberfläche von Myspoti 2.0.0-beta.8 mit dem Funktions- und Sicherheitsumfang von Spotilol Lite. Installiert sich neben Spotilol Lite und braucht eine eigene Anmeldung.

### Von Spotilol übernommen
- Verbindung Seite → App nur über Web-Message-Listener für exakt `https://open.spotify.com`, ohne JavaScript-Interface-Rückfall.
- Wiedergabe-Relay nur zu exakten Spotify-Hosts, mit Größen- und Zeitlimits, ohne Weiterleitungen; Antworten nur an das anfragende Dokument.
- Navigation, Widevine-Freigabe und direkte Anfragen per exaktem HTTPS-Host; Logs geschwärzt.
- Blockliste wie Spotilol inklusive Spotifys Ereignis-Telemetrie (`gabo-receiver-service`).
- Verschlüsselte Kontoprofile (Einstellungen → Konten) und schaltbares Diagnoseprotokoll.

### Gegenüber Myspoti entfernt
- SpotiFLAC- und alle anderen Downloads, Android Auto, YouTube-Musikvideos, Liedtexte und Übersetzung von Drittanbietern, In-App-Updater, Spendenhinweise, Widget.
