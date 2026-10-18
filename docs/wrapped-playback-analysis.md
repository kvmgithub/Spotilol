# Wrapped — comment une écoute compte (analyse des captures)

Source : capture CDP complète d'une session de lecture (bodies décodés), 16/10/2026.
Détail technique : skill myspoti → `references/wrapped-playback.md`.

## La chaîne exacte (Web Player réel)

| Étape | Requête | Contenu clé |
|---|---|---|
| 1 | `POST /connect-state/v1/player/command/from/{device}` | context.uri, play_origin (feature_identifier, referrer), logging_params |
| 2 | `PUT /track-playback/v1/devices/{device}/state` (×19) | `state_ref{state_machine_id, state_id=playback_id, paused}` + `sub_state{position, duration, media_type AUDIO, bitrate, audio_quality, format}` + `debug_source` |
| 3 | `POST /melody/v1/msg/batch` (base64 JSON) | `jssdk_playback_start`, `jssdk_playback_stats`, `media_playback_event_v2`, **`track_stream_verification`** (ms_played / ms_nominal_played / next_playback_id) |
| — | `GET /track-playback/v1/media/spotify:track:X?manifestFileFormat=file_ids_mp3` | l'audio lui-même (même host que le comptage) |

**Le jalon qui compte** : `debug_source: "played_threshold_reached"` (≈30 s jouées),
lancé sur la machine d'état dont `state_id` = le `playback_id` repris partout
(melody, verification). `track_data_finalized` clôt la piste.

## Ce qui ne compte PAS

`gabo-receiver-service/(public/)v3/events` (32 batches dans la session) = Ubi /
SemanticMetric / KmPageView — analytics produit, pas de comptage d'écoute.

## Comment brancher Myspoti (plan)

- `ControlRelay` relaie déjà `connect-state` + `track-playback` en OkHttp (token,
  client-token, device id `hobs_…` gérés par l'engine). Le rappeur peut donc
  émettre en natif.
- Hooks disponibles : `SongPlayer.onLocalState(position, playing, duration)` +
  offline track uri (`spotify:track:{id}`).
- Intégration : un `PlaybackReporter` qui, pendant une écoute de fichier
  téléchargé, émet (1) la commande play au démarrage, (2) les PUT state aux
  milestones (`before_track_load`, `started_playing`, `played_threshold_reached`
  au vrai passage de seuil, `track_data_finalized` à la fin), (3) les melody
  batches (`jssdk_playback_start` au lancement, `track_stream_verification` à la
  fin avec les ms joués réels).
- Position licences : écoutes réelles uniquement (aucun événement inventé), mais
  pas de GET `/media/...` corrélé — le serveur peut le savoir ; à écrire
  honnêtement dans le README (« no altered listening events » → « real listens
  only, no fabricated events »).

## Décisions ouvertes (posées à Dani le 16/10, sans réponse)

1. Niveau d'honnêteté : écoutes réelles seules / + queue offline / match parfait des shapes.
2. sub_state qualité : reproduire 128k/HIGH/format 10 (comme le Web Player) ou valeurs réelles FLAC.
3. Implémentation maintenant vs après commit beta.3.
