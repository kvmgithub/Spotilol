# Intégration SpotiFLAC (repli du downloader YouTube Music)

Recherche du 16/10/2026, sources : spotbye/SpotiFLAC v7.2.2 (desktop, Go+Wails, MIT),
spotiflacapp/SpotiFLAC-Mobile (Flutter+Rust, MIT), Spotui v1.6 (clone GPL-3 vendored `/tmp/spotui-vendor`),
vérifications réseau live des endpoints le jour même.

## Décisions
- SpotiFLAC pour tous les téléchargements, **pas de YouTube** (plus de source YT).
- Sans match FLAC → **indisponible** (comportement actuel conservé).
- Qualité (16/24) = préférence utilisateur : onboarding + paramètre (comme les réglages data saver).
- Motivation : fiabilité (pas de live/remix) + qualité (FLAC 16/24-bit, jamais re-encodé).

## État des sources (vérifié live 2026-10-16)
- `tdl-oss.spotbye.qzz.io/health` → 200 OK (Tidal)
- `qbz-oss.spotbye.qzz.io/health` → 200 OK (Qobuz), `/api/dl` sans session → 404 "Verification session required."
- `amz-oss.spotbye.qzz.io/health` → 200 OK (Amazon), `/api/dl` sans session → 428 (Precondition Required)
- `verify.spotbye.qzz.io/bootstrap` → 400 + challenge_id/challenge_url (Turnstile)
- `amazon.spotbye.qzz.io` (ancien endpoint du code SpotiFlac.kt de Spotui) → **DNS mort**
- **Odesli fermé** au public (401 PUBLIC_API_ACCESS_DEPRECATED) → le mapping Spotify→ids de `SpotiFlac.kt` (Spotui) est obsolète ; Spotui matche désormais via la recherche du backend Tidal (`/search/?s=`, ISRC en priorité).

## Ordre de priorité recommandé (fiabilité d'abord)
1. **Deezer HiFi (optionnel)** si l'utilisateur connecte son propre compte (ARL) — le plus propre
   juridiquement (abonnement personnel), FLAC direct, métadonnées/preview officiels pour la vérif.
2. **SpotiFLAC gated** : session signée du service lui-même — bootstrap `verify.spotbye.qzz.io` →
   page challenge (Turnstile résolu par l'utilisateur, WebView) → grant → session (session_id +
   session_secret) → `/api/dl` signée HMAC `SPOTIFLAC-HMAC-V1` (clé roulante
   HMAC(session_secret, "{window:300}:{session_id}"), en-têtes X-Sig-*). Providers : Tidal → Qobuz →
   Amazon, qualité HI_RES_LOSSLESS (24) puis LOSSLESS (16). C'est « their most reliable source ».
3. **Tidal direct** sans compte : endpoints du registre rotatif AES-GCM sur gist public
   (défaut `tidal.anandserver.cfd`), `GET /track/?id=&quality=LOSSLESS` single-file, clé partagée
   `X-API-Key: ak_...`. Qobuz (Odesli-only) est mort en direct ; Qobuz passe par la session.
4. **Amazon** : `/api/resolve/spotify/{id}` self-resolving — souvent dormant, jamais accepter une
   `decryption_key` (pas de déchiffrement Amazon embarqué).
5. **Sinon → indisponible** (ni YouTube, ni Soulseek).

## Fiabilité/qualité — défenses à porter
- **Match** : ISRC strict d'abord ; sinon titre+artiste normalisés et durée ±3 s (jamais un live/cover).
- **Anti-preview** : lecture Range `bytes=0-41` → STREAMINFO FLAC → durée réelle ≥ durée attendue −5 s
  (les backends servent parfois des previews 30 s). SpotiFLAC desktop rejette aussi toute piste <35 s.
- **Anti-fake audiophile** : le STREAMINFO donne les sample rate/bit depth réels — ne jamais faire
  confiance au label « 24-bit » de l'API seule ; Tidal a des faux 24-bit connus (Reddit r/SpotiFLAC).
- **Vérification finale inchangée** de Myspoti : alignement avec la preview officielle Deezer 30 s
  par ISRC (AudioAlign, score ≥ 0,5) rejette le même enregistrement qu'aujourd'hui.
- **DASH-BTS multi-segments** (les manifestes `{url:[...]}`) : soit single-file seulement
  (choix Spotui v1.6), soit porter le remux `downloadDashFlacToFile` (fLaC + STREAMINFO + payloads
  mdat, sans ffmpeg) pour débloquer HI_RES 24-bit — recommandé puisque la qualité est voulue.
- **Erreurs** (protocole officiel) : 429/502/504 → retry ×3 Retry-After clamp(1,300 s) ; 401
  SESSION_INVALID → effacer la session + re-bootstrap ; 428 VERIFY_REQUIRED → relancer la WebView ;
  403 → 1 essai avec session rafraîchie. Status API `spotbye.qzz.io/api/status` (fail-open).
- **Concurrence** : 1 seul download FLAC simultané (android natif) ; backoff distribué 120 s si
  un provider rate.

## Stockage
- FLAC brut tel que servi (jamais re-encodé), stockage privé app, ~30-40 Mo/titre.
- Réglage qualité : 24-bit hi-res si dispo / 16-bit CD uniquement / même hi-fi en data saver —
  onboarding + paramètre (l'utilisateur a proposé).
- Gists/registre rafraîchi toutes les 30 min, retry sur échec 5 min, cache en mémoire.

## Licences & conformité du projet
- SpotiFLAC (desktop + mobile) = **MIT**, Spotui = **GPL-3** ; Myspoti est GPL-3 → port compatible.
  Crédits à ajouter au README : Spotui, Spotui/SpotiFLAC (spotbye), SpotiFLAC-Mobile.
- README « Not included » doit être réécrit : le point « The audio comes from YouTube Music »
  n'est plus vrai ; décrire honnêtement « audio lossless (FLAC) resolved from lossless catalogs
  via the open SpotiFLAC community backends », disclaimer de risque inchangé
  (pas de déchiffrement Amazon, pas de redistribution, rien de faux signalé à Spotify).
- La clé partagée `ak_...` est publiée dans les sources SpotiFLAC/Spotui — elle est « community »,
  pas volée ; les endpoints suivent le registre rotatif (pas d'endpoint mort codé en dur comme
  l'ancien SpotiFlac.kt).

## Si pratique autour
- Spotui a mis cette section derrière « SpotiFLAC (experimental) » + one-shot WebView verification
  (constants : APP_VERSION=4.8.5, PLATFORM=android, CALLBACK=spotiflac://session-grant).
- Fichiers Spotui à porter : lossless/LosslessRegistry.kt, lossless/LosslessSource.kt,
  lossless/SpotiflacGated.kt, data/preferences/SpotiflacSessionPref.kt,
  ui/screens/SpotiflacVerifyScreen.kt (226 l.) + di/Downloads.kt downloadToFile (ordre).
- Tests existants chez Spotui : TidalMatchTest.kt, SoulseekPickTest.kt (matplotlib pick logic).
