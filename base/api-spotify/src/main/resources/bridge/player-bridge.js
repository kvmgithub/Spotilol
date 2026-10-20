/*
 * Myspoti player bridge.
 *
 * Runs inside the official Web Player page and exposes its player through one
 * small, host-agnostic contract. The same file is loaded by the Android WebView
 * and by desktop Chrome over CDP, so every command can be tested on a PC.
 *
 * Host -> page: window.Myspoti.call(id, method, args)  (also returns a Promise)
 * Page -> host: one JSON string per message, sent to the first host channel found:
 *   - window.MyspotiHost.postMessage(json)  Android web message listener (open.spotify.com only)
 *   - window.__mspHostPost(json)            CDP Runtime.addBinding
 * Messages: {type: "ready"} | {type: "state", state} | {type: "queue", queue}
 *         | {type: "result", id, ok, value | error} | {type: "error", error}
 *         | {type: "boost", sound, src, state | error}  (see "volume boost")
 *
 * The bridge only reads the page's own service registry; it patches nothing.
 */
(() => {
  // Injected again (a second page-finished event for the same page): say ready again only
  // when the host asks for it (window.__mspReannounce, set when it is not ready). A second
  // "ready" for one page load would run every ready collector twice (state refresh, web
  // token, audio quality, volume).
  if (window.Myspoti && window.Myspoti.version) {
    if (window.Myspoti.announce && window.__mspReannounce) {
      window.__mspReannounce = false;
      window.Myspoti.announce();
    }
    return;
  }

  const VERSION = 1;
  const READY_TIMEOUT_MS = 60000;

  const post = msg => {
    const json = JSON.stringify(msg);
    try {
      if (window.MyspotiHost && typeof window.MyspotiHost.postMessage === 'function') return window.MyspotiHost.postMessage(json);
      if (typeof window.__mspHostPost === 'function') return window.__mspHostPost(json);
    } catch (e) { /* host gone: nothing to report to */ }
  };

  // --- service lookup -------------------------------------------------------

  const findPlatform = () => {
    const host = document.getElementById('main') || document.body;
    const roots = [host, ...host.querySelectorAll('*')];
    for (const el of roots) {
      const key = Object.keys(el).find(k => k.startsWith('__reactContainer$'));
      if (!key) continue;
      const stack = [el[key]];
      while (stack.length) {
        const f = stack.pop();
        const v = f.memoizedProps && f.memoizedProps.value;
        if (v && typeof v === 'object' && typeof v.getRegistry === 'function') return v;
        if (f.sibling) stack.push(f.sibling);
        if (f.child) stack.push(f.child);
      }
    }
    return null;
  };

  let services = null;
  const resolveServices = () => {
    const platform = findPlatform();
    if (!platform) return null;
    const registry = platform.getRegistry();
    const byName = {};
    for (const key of registry._map.keys()) {
      if (key && key.description) byName[key.description] = key;
    }
    const get = name => {
      if (!byName[name]) throw new Error(`service ${name} not found`);
      return registry.resolve(byName[name]);
    };
    const player = get('PlayerAPI');
    if (!player || typeof player.getState !== 'function') return null;
    return {
      platform,
      player,
      playback: get('PlaybackAPI'),
      library: get('LibraryAPI'),
      token: get('TokenProvider'),
      graphql: platform.getGraphQLLoader(),
      byName,
      resolve: name => get(name),
    };
  };

  // --- state mapping --------------------------------------------------------

  const image = images => {
    if (!images || !images.length) return null;
    return [...images].sort((a, b) => (b.width || 0) - (a.width || 0))[0].url;
  };

  // Ads come in the same queue as tracks, with `type: "ad"` and a title instead of a name.
  // Some markets give them a track-like uri: the metadata still flags them.
  const isAd = item => item.type === 'ad' || /^spotify:ad:/.test(item.uri || '') ||
    item.provider === 'ad' || !!item.advertiser ||
    (item.metadata && (item.metadata.is_advertisement === 'true' || item.metadata.is_advertisement === true || !!item.metadata.ad_id));
  const kind = item => isAd(item) ? 'ad'
    : /^spotify:episode:/.test(item.uri) ? 'episode' : 'track';

  const track = item => item && {
    kind: kind(item),
    uri: item.uri,
    uid: item.uid || null,
    name: item.name || item.title || '',
    advertiser: item.advertiser || null,
    durationMs: item.duration ? item.duration.milliseconds : null,
    artists: (item.artists || []).map(a => ({ uri: a.uri, name: a.name })),
    album: item.album ? { uri: item.album.uri, name: item.album.name } : null,
    image: image(item.images || (item.album && item.album.images)),
    explicit: !!item.isExplicit,
    mediaType: item.mediaType || 'audio',
    provider: item.provider || null,
  };

  const lastReal = items => (items || []).filter(i => kind(i) !== 'ad');
  const mapState = s => s && {
    track: track(s.item),
    contextUri: s.context ? s.context.uri : null,
    contextName: s.context && s.context.metadata ? s.context.metadata.context_description || null : null,
    previous: track(lastReal(s.previousItems).slice(-1)[0]),
    next: track(lastReal(s.nextItems)[0]),
    isPaused: !!s.isPaused,
    isBuffering: !!s.isBuffering,
    positionMs: s.positionAsOfTimestamp,
    timestamp: s.timestamp,
    durationMs: s.duration,
    shuffle: !!s.shuffle,
    repeat: s.repeat, // 0 off, 1 context, 2 track
    speed: s.speed,
    restrictions: s.restrictions || {},
    playbackId: s.playbackId || null,
  };

  const mapQueue = q => q && {
    current: track(q.current),
    queued: (q.queued || []).map(track),
    nextUp: (q.nextUp || []).map(track),
  };

  // --- serialization --------------------------------------------------------

  const methodsOf = obj => {
    const names = new Set();
    for (let p = obj; p && p !== Object.prototype; p = Object.getPrototypeOf(p)) {
      for (const n of Object.getOwnPropertyNames(p)) {
        if (n === 'constructor' || n.startsWith('_')) continue;
        try { if (typeof p[n] === 'function') names.add(n); } catch (e) { /* getter threw */ }
      }
    }
    return [...names].sort();
  };

  // Plain JSON copy of whatever a service returns: drops functions, breaks cycles,
  // turns Maps/Sets into objects/arrays and BigInts into strings.
  const toJson = (value, depth = 0, seen = new WeakSet()) => {
    if (value === null || value === undefined) return null;
    const t = typeof value;
    if (t === 'bigint') return value.toString();
    if (t === 'function' || t === 'symbol') return undefined;
    if (t !== 'object') return value;
    if (depth > 12 || seen.has(value)) return null;
    seen.add(value);
    if (value instanceof Date) return value.toISOString();
    if (value instanceof Map) value = Object.fromEntries(value);
    if (value instanceof Set) value = [...value];
    if (ArrayBuffer.isView(value)) return Array.from(value);
    if (Array.isArray(value)) return value.map(v => { const j = toJson(v, depth + 1, seen); return j === undefined ? null : j; });
    const out = {};
    for (const k of Object.keys(value)) {
      if (k.startsWith('_')) continue;
      const j = toJson(value[k], depth + 1, seen);
      if (j !== undefined) out[k] = j;
    }
    return out;
  };

  // The page player's own media elements: kept inside its objects, never in the document.
  const playerMedia = async () => {
    const found = new Set();
    const sdk = need().resolve('PlayerSDK');
    const harmony = sdk && sdk.harmony;
    const streamer = harmony && harmony._streamer;
    const roots = [harmony];
    if (streamer && streamer._playerPromise) roots.push(await streamer._playerPromise);
    const seen = new WeakSet();
    const walk = (o, depth) => {
      if (!o || typeof o !== 'object' || seen.has(o) || depth > 6) return;
      seen.add(o);
      if (o instanceof HTMLMediaElement) { found.add(o); return; }
      if (o instanceof Node || o instanceof Window) return;
      let keys = [];
      try { keys = Object.keys(o); } catch (e) { return; }
      for (const k of keys.slice(0, 80)) {
        let v;
        try { v = o[k]; } catch (e) { continue; }
        walk(v, depth + 1);
      }
    };
    roots.forEach(r => walk(r, 0));
    return [...found];
  };

  // The page's own audio player (PlayerSDK.harmony's streamer), once it exists.
  const localPlayer = async () => {
    const sdk = need().resolve('PlayerSDK');
    const streamer = sdk && sdk.harmony && sdk.harmony._streamer;
    if (!streamer || !streamer._playerPromise) return null;
    return Promise.race([streamer._playerPromise, new Promise(r => setTimeout(() => r(null), 2000))]);
  };

  // Not heard, yet not "muted audio" to Chromium (see 'media.mute').
  const SILENT = 0.001;

  // A fade is a list of setTimeout steps: a second fade, a plain volume set or a mute must
  // cancel the pending ones, or they would override it seconds later (the up-ramp of a
  // crossfade undone by the tail of its own down-ramp, a restored volume pulled down again).
  let fadeTimers = [];
  const cancelFade = () => { fadeTimers.forEach(clearTimeout); fadeTimers = []; };

  // --- volume boost ---------------------------------------------------------
  //
  // Above 100 %: the page's media elements play through Web Audio, a gain and then a limiter
  // (a compressor at -1 dBFS, ratio 20). An element routed into Web Audio cannot go back to
  // direct output before the page reloads, so nothing is built until a boost above 1 is asked
  // for, and at 1 the graph is neutral (gain 1, ratio 1). During an ad (window.__mspMuted) the
  // gain is 0 as well, whatever Chromium does with the element's own volume in the graph.
  //
  // Chromium hands Widevine-decrypted audio to Web Audio and applies the element's volume
  // there (fades and ad muting keep working); a cross-origin source without CORS comes through
  // as silence. A probe listens while an element
  // plays: {type: 'boost', sound: true} once per page when sound comes through, sound: false
  // after BOOST_SILENT_S seconds of playing in silence (the host then reloads the page).
  const BOOST_MAX = 2;
  const BOOST_SILENT_S = 10;
  let boost = null;

  const srcKind = el => {
    const s = el.currentSrc || '';
    if (s.startsWith('blob:')) return 'blob';
    try { return new URL(s, location.href).origin === location.origin ? 'same-origin' : 'cross-origin'; } catch (e) { return 'none'; }
  };

  // Routes every media element the page played (and the player's own) into the graph, once.
  const boostRoute = async () => {
    if (!boost) return;
    const player = await localPlayer().catch(() => null);
    const els = new Set(window.__mspMedia || []);
    if (player && player._player instanceof HTMLMediaElement) els.add(player._player);
    for (const el of els) {
      if (!(el instanceof HTMLMediaElement) || boost.routed.has(el)) continue;
      boost.routed.add(el);
      try {
        boost.ctx.createMediaElementSource(el).connect(boost.gain);
        boost.count++;
      } catch (e) {
        post({ type: 'boost', error: String(e && e.message || e).slice(0, 200) });
      }
    }
    if (boost.ctx.state === 'suspended') boost.ctx.resume().catch(() => {});
  };

  const boostProbe = () => {
    const buf = new Float32Array(boost.analyser.fftSize);
    let silent = 0, last = -1, heard = false;
    const timer = setInterval(async () => {
      const player = await localPlayer().catch(() => null);
      const el = player && player._player;
      if (!(el instanceof HTMLMediaElement) || !boost.routed.has(el)) return;
      const moving = !el.paused && el.currentTime !== last;
      last = el.currentTime;
      if (!moving) { silent = 0; return; }
      boost.analyser.getFloatTimeDomainData(buf);
      let peak = 0;
      for (const v of buf) peak = Math.max(peak, Math.abs(v));
      const level = Math.round(el.volume * 1000) / 1000;
      if (peak > 0.0001) {
        silent = 0;
        if (!heard) { heard = true; post({ type: 'boost', sound: true, src: srcKind(el), state: boost.ctx.state, level }); }
        return;
      }
      // Silence counts only where sound is due (Chromium applies the element's volume in the graph).
      if (window.__mspMuted || el.volume < 0.05 || boost.gain.gain.value === 0) { silent = 0; return; }
      if (++silent < BOOST_SILENT_S) return;
      clearInterval(timer);
      post({ type: 'boost', sound: false, src: srcKind(el), state: boost.ctx.state, level });
    }, 1000);
  };

  // "Is this page Spotify Connect's active device": a full device-list round trip, so it is
  // read at most once every ACTIVE_CACHE_MS and reused (a transfer happens minutes apart).
  const ACTIVE_CACHE_MS = 6000;
  let activeValue = null, activeAt = 0;
  const activeDevice = async () => {
    const now = Date.now();
    if (activeValue !== null && now - activeAt < ACTIVE_CACHE_MS) return activeValue;
    try {
      const me = (await need().resolve('ConnectDevicesAPI').getDevices()).find(d => d.isLocal);
      activeValue = !!(me && me.isActive);
      activeAt = now;
    } catch (e) { /* unknown: the caller keeps the last answer */ }
    return activeValue;
  };

  // The element volume the page player itself sets for its volume (cubic curve or not).
  const ownLevel = player => {
    const v = typeof player._playerVolume === 'number' ? player._playerVolume : 1;
    return player._cubicVolume ? v * v * v : v;
  };

  // --- commands -------------------------------------------------------------

  const need = () => {
    if (!services) throw new Error('not ready');
    return services;
  };

  // --- ads ------------------------------------------------------------------
  // The page's own ad managers drive the audio ads and their mute. SpotX patches the bundled
  // web player to force `adsEnabled` off; the very same managers are reachable at runtime through
  // the service registry, so they are turned off here instead of patching a bundle we do not ship.
  //
  // `disable` alone is not enough: the page re-arms its managers at every ad opportunity (a
  // "Test ad" still came through on a free account, 19/10 02:29). `enable`/`disable` sit on the
  // prototype and `enabled` is a plain field, so the instance is shadowed here: after this, the
  // page cannot arm an ad again for the life of the page.
  let adsLatched = false;
  const adsOff = () => {
    if (!services) return 'not ready';
    if (adsLatched) return 'already off';
    let audio = null;
    try { audio = services.resolve('AdManagers').audio; } catch (e) { return 'unresolved'; }
    if (!audio) return 'no ad manager';
    const done = [];
    for (const [label, mgr] of [['audio', audio], ['inStream', audio.inStreamApi]]) {
      if (!mgr) continue;
      try {
        if (typeof mgr.disable === 'function') { mgr.disable(); done.push(label); }
        // The page's own `enable` is replaced on the instance: it can only ever answer "not on".
        if (typeof mgr.enable === 'function') {
          Object.defineProperty(mgr, 'enable', { value: () => false, writable: true, configurable: true });
        }
        if ('enabled' in mgr) mgr.enabled = false;
      } catch (e) { /* already off */ }
    }
    return done.length ? (adsLatched = true, done.join(',')) : 'nothing to disable';
  };

  /**
   * Ends the ad the in-stream ad manager owns, its own way (a skip of the ad break, not of the
   * song). The app asks for this before it falls back to reloading the page, which is what a
   * "Test ad" used to cost: a visible reload of the whole player.
   */
  const adsSkip = () => {
    if (!services) return 'not ready';
    try {
      const api = services.resolve('AdManagers').audio.inStreamApi;
      if (api && typeof api.skipToNext === 'function') { api.skipToNext(); return 'inStream.skipToNext'; }
    } catch (e) { return 'failed: ' + e; }
    return 'no lever';
  };

  const commands = {
    'bridge.version': () => VERSION,
    'ads.off': () => adsOff(),
    'ads.skip': () => adsSkip(),

    'player.state': () => mapState(need().player.getState()),
    'player.queue': () => mapQueue(need().player.getQueue()),
    // {contextUri, trackUri?, trackUid?, index?, positionMs?}
    'player.play': a => {
      const skipTo = {};
      if (a.trackUri) skipTo.uri = a.trackUri;
      if (a.trackUid) skipTo.uid = a.trackUid;
      if (Number.isInteger(a.index)) skipTo.index = a.index;
      const extra = {};
      if (Object.keys(skipTo).length) extra.skipTo = skipTo;
      if (a.positionMs) extra.seekTo = a.positionMs;
      // A given order (sorted playlist): the context plays as one page of those songs.
      const context = Array.isArray(a.order) && a.order.length
        ? { uri: a.contextUri, url: 'context://' + a.contextUri + '?order=' + Date.now(), pages: [{ items: a.order.map(uri => ({ uri })) }] }
        : { uri: a.contextUri };
      return need().player.play(context, {}, extra);
    },
    'player.pause': () => need().player.pause(),
    'player.resume': () => need().player.resume(),
    'player.seek': a => need().player.seekTo(a.positionMs),
    'player.next': () => need().player.skipToNext(),
    'player.previous': () => need().player.skipToPrevious(),
    'player.setShuffle': a => need().player.setShuffle(!!a.on),
    'player.setRepeat': a => need().player.setRepeat(a.mode),
    'player.setSpeed': a => need().player.setSpeed(a.speed),
    // Streaming quality, as the desktop app's setting: "auto", "low", "normal", "high",
    // "very_high". The page's own player (PlayerSDK.harmony) picks the files with it.
    // What the page plays now: bitrate, codec and the chosen quality level.
    'player.audioInfo': async () => {
      const sdk = need().resolve('PlayerSDK');
      const streamer = sdk && sdk.harmony && sdk.harmony._streamer;
      const player = streamer && streamer._playerPromise ? await streamer._playerPromise : null;
      const content = player && player._currentContent;
      return {
        bitrate: content && content._bitrate || 0,
        mime: content && content._mimeType || '',
        level: player && player._preferredMediaPlaybackQuality && player._preferredMediaPlaybackQuality.audio || '',
      };
    },
    'player.setAudioQuality': async a => {
      const sdk = need().resolve('PlayerSDK');
      const harmony = sdk && sdk.harmony;
      // The page's own streamer first: the public entry refuses while Spotify Connect
      // calls another device the active one ("remote player"), yet this page plays.
      const target = [harmony && harmony._streamer, harmony]
        .find(t => t && typeof t.setMediaPlaybackQuality === 'function');
      if (!target) throw new Error('no audio quality setting');
      return !!(await target.setMediaPlaybackQuality({ audio: a.level }));
    },
    // Loads the song after this one now (its file, first segment and license), as the page
    // itself does only 10 s before the end: a skip then starts that much sooner. -> the result.
    'player.preloadNext': async () => {
      const sdk = need().resolve('PlayerSDK');
      const lp = sdk && sdk.harmony && sdk.harmony._streamer && sdk.harmony._streamer._listPlayer;
      if (!lp || typeof lp._preloadUpcomingTrack !== 'function') return null;
      return String(await lp._preloadUpcomingTrack());
    },
    'queue.add': a => need().player.addToQueue(a.uris.map(uri => ({ uri }))),
    'queue.remove': a => need().player.removeFromQueue(a.items),
    'queue.clear': () => need().player.clearQueue(),
    // Jumps to a queued or upcoming item.
    'queue.skipTo': a => need().player.skipToNext({ uri: a.uri, uid: a.uid }),
    // {uris, before: {uri, uid}} : "play next" puts the tracks before the first queued item.
    'queue.insert': a => need().player.insertIntoQueue(a.uris.map(uri => ({ uri })), { before: a.before }),
    // {items: [{uri, uid}], before | after: {uri, uid}}
    'queue.reorder': a => need().player.reorderQueue(a.items, a.after ? { after: a.after } : { before: a.before }),

    // Mutes the page's own media elements (ads), whatever Spotify Connect says about the
    // volume. Elements are known from the host's document-start hook (window.__mspMedia),
    // or found in the document. {on} -> how many elements were set.
    //
    // Not `muted`, and never volume 0: Chromium pauses a hidden page's media that starts (a
    // new song, a restart, a seek) muted or at volume 0, and the song after the ad stayed
    // paused. A volume of SILENT is not heard and keeps playback going.
    'media.mute': async a => {
      cancelFade();
      window.__mspMuted = !!a.on;
      if (boost) boost.gain.gain.value = a.on ? 0 : boost.level;
      const player = await localPlayer().catch(() => null);
      const els = new Set([...(window.__mspMedia || []), ...document.querySelectorAll('audio, video'), ...await playerMedia()]);
      for (const el of els) {
        el.muted = false;
        el.volume = a.on ? SILENT : (player && el === player._player ? ownLevel(player) : 1);
      }
      return els.size;
    },

    // The page player's own media element now: what is really heard, whatever the state says.
    // active: this page is Spotify Connect's active device (else the state is another device's).
    // level: the element's volume (0..1), which local fades move; own: the level the page
    // player itself wants there. playerVolume: what the page player itself is set to, read
    // here too so the host's periodic volume check costs no second call.
    // -> {paused, muted, volume, level, own, time, readyState, ended, playerVolume, active} | null (no element yet)
    'media.state': async () => {
      const player = await localPlayer();
      const el = player && player._player;
      if (!(el instanceof HTMLMediaElement)) return null;
      let playerVolume = null;
      try { playerVolume = await need().playback.getVolume(); } catch (e) { /* unknown */ }
      return { paused: el.paused, muted: el.muted, volume: el.volume, level: el.volume, own: ownLevel(player), time: el.currentTime, readyState: el.readyState, ended: el.ended, playerVolume, active: await activeDevice() };
    },
    // The media element's volume, on this phone only: no Spotify Connect command (refused on
    // some accounts, slow on a busy network) and nothing reported to Spotify, as the player's
    // own setVolume would. Fades go through it. {level} | {restore: true} (the player's own
    // volume back) -> the level set.
    'media.volume': async a => {
      const player = await localPlayer();
      const el = player && player._player;
      if (!(el instanceof HTMLMediaElement)) return null;
      cancelFade();
      // Never 0: see 'media.mute'.
      el.volume = a.restore ? ownLevel(player) : Math.min(1, Math.max(SILENT, Number(a.level) || 0));
      return el.volume;
    },

    // A whole fade in one call: the page applies the steps itself, so the host sends the play
    // command while the sound goes down instead of waiting for every step (a round trip each).
    // {steps: [level, ...], ms, restore: true?} -> true. Levels are never 0: see 'media.mute'.
    'media.fade': async a => {
      const player = await localPlayer();
      const el = player && player._player;
      if (!(el instanceof HTMLMediaElement)) return null;
      const steps = (Array.isArray(a.steps) ? a.steps : []).map(Number);
      const per = Math.max(1, Number(a.ms) || 12);
      const set = v => { el.volume = Math.min(1, Math.max(SILENT, v)); };
      // A ramp replaces the one before it (a crossfade's up-ramp must not be pulled back down
      // by steps still pending from its down-ramp).
      cancelFade();
      fadeTimers = steps.map((v, i) => setTimeout(() => set(v), i * per));
      if (a.restore) fadeTimers.push(setTimeout(() => { el.volume = ownLevel(player); }, steps.length * per));
      return true;
    },

    // The volume boost (see "volume boost"): {level: 1..2} -> {level, routed, state}.
    'media.boost': async a => {
      const level = Math.min(BOOST_MAX, Math.max(1, Number(a.level) || 1));
      if (!boost && level <= 1) return { level, routed: 0, state: 'off' };
      if (!boost) {
        const ctx = new AudioContext({ latencyHint: 'playback' });
        const gain = ctx.createGain();
        const limiter = ctx.createDynamicsCompressor();
        limiter.knee.value = 0;
        limiter.attack.value = 0.002;
        limiter.release.value = 0.2;
        const analyser = ctx.createAnalyser();
        gain.connect(limiter).connect(ctx.destination);
        gain.connect(analyser);
        boost = { ctx, gain, limiter, analyser, level, routed: new WeakSet(), count: 0 };
        boostProbe();
      }
      boost.level = level;
      boost.gain.gain.value = window.__mspMuted ? 0 : level;
      boost.limiter.threshold.value = level > 1 ? -1 : 0;
      boost.limiter.ratio.value = level > 1 ? 20 : 1;
      await boostRoute();
      return { level, routed: boost.count, state: boost.ctx.state };
    },

    'volume.get': () => need().playback.getVolume(),
    'volume.set': a => need().playback.setVolume(a.level),

    'library.contains': a => need().library.contains(...a.uris),
    'library.add': a => need().library.add({ uris: a.uris }),
    'library.remove': a => need().library.remove({ uris: a.uris }),

    // Generic access to the page's services (PlaylistAPI, RootlistAPI, ShowAPI, ConnectAPI...).
    // Typed wrappers live on the host side; this keeps the bridge small.
    'service.list': () => {
      const out = {};
      for (const name of Object.keys(need().byName)) {
        let v;
        try { v = need().resolve(name); } catch (e) { continue; }
        if (v && typeof v === 'object') out[name] = methodsOf(v);
      }
      return out;
    },
    // {service, method, args[]}
    'service.call': async a => {
      const svc = need().resolve(a.service);
      if (!svc || typeof svc[a.method] !== 'function' || a.method.startsWith('_')) {
        throw new Error(`no method ${a.service}.${a.method}`);
      }
      return toJson(await svc[a.method](...(a.args || [])));
    },

    // A playlist's cover, as the page's "Edit details" sets it: the JPEG goes to the image
    // upload service, then its token to the playlist. No jpeg (null): the cover is removed.
    // {uri, jpeg: base64 | null}
    'playlist.setCover': async a => {
      const playlists = need().resolve('PlaylistAPI');
      let token = null;
      if (a.jpeg) {
        const bytes = Uint8Array.from(atob(a.jpeg), c => c.charCodeAt(0));
        token = await playlists.uploadImage(new Blob([bytes], { type: 'image/jpeg' }));
      }
      await playlists.updateDetails(a.uri, { imageUploadToken: token });
      return true;
    },

    // Any persisted GraphQL operation of the Web Player, sent by the page's own loader
    // (its auth, client token and retries). {name, hash, variables}
    'gql.query': async a => {
      const def = { name: a.name, operation: a.operation || 'query', sha256Hash: a.hash, value: null };
      return need().graphql(def, a.variables || {});
    },

    // Web token for the HTTP API, refreshed by the page itself.
    'token.get': async () => {
      const t = await need().token.getToken();
      // clientId and appVersion let the host mint its own client token for direct HTTP calls.
      return t && {
        accessToken: t.accessToken,
        expiresAtMs: t.accessTokenExpirationTimestampMs,
        clientId: t.clientId,
        appVersion: need().platform.version,
        isAnonymous: !!t.isAnonymous,
      };
    },
  };

  const call = async (id, method, args) => {
    try {
      const fn = commands[method];
      if (!fn) throw new Error(`unknown method ${method}`);
      const value = await fn(args || {});
      const out = value === undefined ? null : value;
      post({ type: 'result', id, ok: true, value: out });
      return out;
    } catch (e) {
      post({ type: 'result', id, ok: false, error: String(e && e.message || e) });
      throw e;
    }
  };

  // --- events ---------------------------------------------------------------

  let lastState = '';
  const pushState = () => {
    if (!services) return;
    const state = mapState(services.player.getState());
    // An ad is on: the page's ad managers are turned off again (it re-arms them per break).
    if (state && state.track && state.track.kind === 'ad') { try { adsOff(); } catch (e) { /* no service */ } }
    const json = JSON.stringify(state);
    if (json === lastState) return;
    lastState = json;
    post({ type: 'state', state });
    // A new element (a new song, an ad) joins the boost.
    if (boost) boostRoute();
  };

  const pushQueue = () => {
    if (services) post({ type: 'queue', queue: mapQueue(services.player.getQueue()) });
  };

  const attach = () => {
    const events = services.player.getEvents();
    events.addListener('update', pushState);
    events.addListener('queue_update', pushQueue);
    // Ads off from the first moment: a break queued before the bridge attached never starts.
    try { adsOff(); } catch (e) { /* no service yet */ }
  };

  // --- boot -----------------------------------------------------------------

  // --- diagnostics (read-only) ----------------------------------------------
  // Playback requests with their status and body size, and uncaught page errors,
  // so the host log shows why a play command did nothing.
  const PLAYBACK = /player\/command|track-playback|connect-state\/v1\/devices|widevine-license|storage-resolve|audio-.*\.scdn\.co|apresolve/;
  try {
    new PerformanceObserver(list => {
      for (const e of list.getEntries()) {
        if (!PLAYBACK.test(e.name)) continue;
        post({ type: 'net', url: e.name.split('?')[0].slice(0, 160), status: e.responseStatus || 0, bytes: e.encodedBodySize || e.transferSize || 0, ms: Math.round(e.duration) });
      }
    }).observe({ type: 'resource', buffered: true });
  } catch (e) { /* no resource timing */ }
  window.addEventListener('error', e => post({ type: 'jserror', error: String(e.message || e.error || '').slice(0, 300) }));
  window.addEventListener('unhandledrejection', e => post({ type: 'jserror', error: String((e.reason && (e.reason.stack || e.reason.message)) || e.reason || '').slice(0, 300) }));

  const announce = () => {
    if (!services) return;
    post({ type: 'ready', version: VERSION });
    lastState = '';
    pushState();
  };

  window.Myspoti = { version: VERSION, call, announce, adsOff, adsSkip, methods: Object.keys(commands) };

  // The page's own UI is never seen. On the home page a song change redraws ~7000 nodes and
  // ~190 images, which held the main thread for ~1 s and delayed the next song's sound; the
  // settings page has ~260 nodes and one image. It must be a real page of the Web Player: a
  // reload (by the page, the WebView or a test) loads the current URL, and an unknown one is
  // served without the player.
  const IDLE_ROUTE = '/preferences';
  const park = () => {
    try {
      const history = services.platform.getHistory && services.platform.getHistory();
      if (history && typeof history.replace === 'function' && location.pathname !== IDLE_ROUTE) history.replace(IDLE_ROUTE);
    } catch (e) { /* the page keeps its route */ }
  };

  const started = Date.now();
  const boot = () => {
    try {
      services = resolveServices();
    } catch (e) {
      services = null;
    }
    if (services) {
      attach();
      park();
      post({ type: 'ready', version: VERSION });
      pushState();
      return;
    }
    if (Date.now() - started > READY_TIMEOUT_MS) {
      post({ type: 'error', error: 'player services not found' });
      return;
    }
    setTimeout(boot, 250);
  };
  boot();
})();
