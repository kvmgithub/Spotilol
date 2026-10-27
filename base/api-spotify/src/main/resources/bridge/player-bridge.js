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

  // The page always sees itself visible (Myspoti 2.0.2): a hidden page has its timers and
  // rendering throttled, and the engine runs with the app in the background (the host already
  // tells Chromium the view is shown). Nothing in this bridge reads the page's visibility.
  try {
    Object.defineProperty(Document.prototype, 'hidden', { configurable: true, get: () => false });
    Object.defineProperty(Document.prototype, 'visibilityState', { configurable: true, get: () => 'visible' });
    Object.defineProperty(Document.prototype, 'webkitHidden', { configurable: true, get: () => false });
    Object.defineProperty(Document.prototype, 'webkitVisibilityState', { configurable: true, get: () => 'visible' });
    document.hasFocus = () => true;
    for (const t of ['visibilitychange', 'webkitvisibilitychange']) {
      document.addEventListener(t, e => e.stopImmediatePropagation(), true);
      window.addEventListener(t, e => e.stopImmediatePropagation(), true);
    }
  } catch (e) { /* keep the real values */ }

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
  const mapState = s => s && xfState(s, {
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
  });

  // The crossfade reads the song's kind, length and speed from here.
  const xfState = (s, out) => {
    xf.kind = out.track ? out.track.kind : null;
    xf.durationMs = s.duration || 0;
    xf.speed = s.speed || 1;
    return out;
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

  // --- audio graph: volume boost and crossfade -------------------------------
  //
  // The page's media elements play through Web Audio once a boost above 100 % or a crossfade
  // is asked for: gain (the boost; 0 during an ad) -> two paths [delay -> gain] -> limiter (a
  // compressor at -1 dBFS, ratio 20 while boosted, neutral otherwise) -> out (local fades) ->
  // speakers. An element routed into Web Audio cannot go back to direct output before the page
  // reloads, so nothing is built before either is asked for; at 100 % without a crossfade the
  // graph is neutral (one path at delay 0 and gain 1, the other silent, ratio 1). During an ad (window.__mspMuted) the gain
  // is 0 as well, whatever Chromium does with the element's own volume in the graph.
  //
  // Chromium hands Widevine-decrypted audio to Web Audio and applies the element's volume
  // there (fades and ad muting keep working); a cross-origin source without CORS comes through
  // as silence. A probe listens while an element
  // plays: {type: 'boost', sound: true} once per page when sound comes through, sound: false
  // after BOOST_SILENT_S seconds of playing in silence (the host then reloads the page).
  const BOOST_MAX = 2;
  const BOOST_SILENT_S = 10;
  // The graph (named after its first use): {ctx, gain, paths: [{delay, g}, {delay, g}], limiter, out, analyser, ...}.
  let boost = null;

  const srcKind = el => {
    const s = el.currentSrc || '';
    if (s.startsWith('blob:')) return 'blob';
    try { return new URL(s, location.href).origin === location.origin ? 'same-origin' : 'cross-origin'; } catch (e) { return 'none'; }
  };

  // Routes every media element the page played (and the player's own) into the graph, once.
  // Local levels (fades, the crossfade, a volume set) with the boost on: after the limiter, on
  // the graph's last gain, while the element stays on the player's own volume. On the element
  // they came before the limiter, which held a boosted song at its ceiling until the level fell
  // below the threshold: the first half of a fade was not heard, the rest dropped at once.
  const boosted = el => !!boost && boost.routed.has(el);
  const setLevel = (el, player, v) => {
    const own = player && el === player._player ? ownLevel(player) : 1;
    const level = Math.min(1, Math.max(SILENT, v));
    if (!boosted(el) || own <= SILENT) { el.volume = level; return; }
    el.volume = own;
    boost.out.gain.setTargetAtTime(Math.min(1, level / own), boost.ctx.currentTime, 0.015);
  };
  const levelOf = el => el.volume * (boosted(el) ? boost.out.gain.value : 1);
  const restoreOwn = (el, player) => {
    el.volume = player && el === player._player ? ownLevel(player) : 1;
    if (boosted(el)) boost.out.gain.setTargetAtTime(1, boost.ctx.currentTime, 0.015);
  };

  // One element into the graph, once; el.__mspId numbers them in the order they joined.
  const routeEl = el => {
    if (!(el instanceof HTMLMediaElement) || boost.routed.has(el)) return;
    boost.routed.add(el);
    try {
      boost.ctx.createMediaElementSource(el).connect(boost.gain);
      el.__mspId = ++boost.count;
      xfWatch(el);
      if (el.__mspId > 1) post({ type: 'boost', routed: el.__mspId });
    } catch (e) {
      post({ type: 'boost', error: String(e && e.message || e).slice(0, 200) });
    }
  };
  // Called by the host's play() hook (document start) before an element's first sound: an
  // element routed later, on the next state push, played its first moments past the graph.
  window.__mspRoute = el => { if (boost) routeEl(el); };

  const boostRoute = async () => {
    if (!boost) return;
    const player = await localPlayer().catch(() => null);
    const els = new Set(window.__mspMedia || []);
    if (player && player._player instanceof HTMLMediaElement) els.add(player._player);
    for (const el of els) routeEl(el);
    if (player && player._player instanceof HTMLMediaElement) xf.el = player._player;
    // Suspended while the player is paused (see xfWatch): a crossfade reserve must not play on.
    if (boost.ctx.state === 'suspended' && (!xf.el || !xf.el.paused)) boost.ctx.resume().catch(() => {});
  };

  const boostProbe = () => {
    const buf = new Float32Array(boost.analyser.fftSize);
    let silent = 0, last = -1, heard = false;
    const timer = setInterval(async () => {
      const player = await localPlayer().catch(() => null);
      const el = player && player._player;
      if (!(el instanceof HTMLMediaElement) || !boost.routed.has(el)) return;
      // Moving means playing: about a second a tick, with data ahead. An element starved by the
      // network (readyState HAVE_CURRENT_DATA or less) creeps on in silence; that is no proof of
      // a broken graph (0.11.1, log seen: a network drop turned the boost and crossfade off).
      const moving = !el.paused && last >= 0 && el.currentTime - last > 0.5 && el.readyState >= 3;
      last = el.currentTime;
      if (!moving) { silent = 0; return; }
      if (boost.ctx.state === 'suspended') { boost.ctx.resume().catch(() => {}); silent = 0; return; }
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

  // The graph, built once per page (see "audio graph").
  const ensureGraph = () => {
    if (boost) return boost;
    const ctx = new AudioContext({ latencyHint: 'playback' });
    const gain = ctx.createGain();
    const paths = [0, 1].map(i => {
      const path = { delay: ctx.createDelay(XF_MAX_S + 4), g: ctx.createGain(), dm: [{ t: 0, v: 0 }], an: ctx.createAnalyser() };
      path.g.gain.value = i === 0 ? 1 : 0;
      path.an.fftSize = 2048;
      return path;
    });
    const mix = ctx.createGain();
    const limiter = ctx.createDynamicsCompressor();
    limiter.knee.value = 0;
    limiter.attack.value = 0.002;
    limiter.release.value = 0.2;
    limiter.threshold.value = 0;
    limiter.ratio.value = 1;
    // out: local fades and volume sets (see setLevel), after the limiter.
    const out = ctx.createGain();
    const analyser = ctx.createAnalyser();
    for (const path of paths) { gain.connect(path.delay).connect(path.g).connect(mix); path.delay.connect(path.an); }
    mix.connect(limiter).connect(out).connect(ctx.destination);
    gain.connect(analyser);
    // What is heard, for the overlap's level probe (see xfProbe).
    const heard = ctx.createAnalyser();
    heard.fftSize = 2048;
    out.connect(heard);
    boost = { ctx, gain, paths, mix, limiter, out, analyser, heard, level: 1, routed: new WeakSet(), count: 0 };
    boostProbe();
    return boost;
  };

  // --- crossfade --------------------------------------------------------------
  //
  // The page plays one song at a time, so the end of one cannot sound under the start of the
  // next. The graph keeps a reserve instead. While a song plays, the element runs k times
  // faster (pitch not preserved), and the delay is laid out so that its read position moves
  // through every stretch of what was written at 1/k of the speed that stretch was written at:
  // tempo and pitch come out exactly as recorded, and what is heard falls behind the page by
  // (k - 1) seconds of song per second. By the song's end the reserve holds its last N seconds.
  // The graph has two such paths. When the next song starts, the old song's path plays its end
  // and fades out while the other path starts at delay 0 with the new song and fades it in,
  // equal power, over the reserve. The new song's reserve is built on its own path from its
  // first seconds, under the fade-in, so neither the switch nor the speed change falls on a
  // moment that is heard at full level; the next song change uses the other path again.
  //
  // A pause suspends the context, so the reserve neither plays on nor gets lost. A seek or a
  // skip drops it (the sound jumps to where the page is). What is heard lags the page by the
  // reserve: the page's player sees the heard time (see xfMask), so slider and lyrics follow the ear.
  const XF_MAX_S = 12;
  const XF_K_MAX = 1.12;
  // A song that ends with less than this left at the last look ended by itself (else: a skip).
  const XF_NATURAL_S = 1.0;
  // Within this of its end the page sees the element's real time (see xfMask).
  const XF_END_REAL_S = 1.5;
  // A song whose speed the page put back this often is left alone (it would fight every change).
  const XF_MAX_RESETS = 3;
  // How long after 'media.ending' the next switch counts as the song's end (see there).
  const XF_ENDING_MS = 4000;
  const xf = { seconds: 0, el: null, k: 1, ours: false, pageRate: 1, pitch: null, segs: [{ at: 0, k: 1 }], cur: 0,
    switchAt: -1e9, lastRem: Infinity, kind: null, durationMs: 0, speed: 1, planned: null, noted: '',
    resets: 0, sets: 0, endingUntil: 0, overlapUntil: 0, probes: [], masked: new WeakSet() };

  // The element's own speed and time, past the masks below.
  const RATE = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'playbackRate');
  const TIME = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'currentTime');
  const realRate = el => RATE.get.call(el);
  const setRealRate = (el, v) => RATE.set.call(el, v);
  const realTime = el => TIME.get.call(el);

  // The page's player must not see the reserve being built: a speed it did not set, or a time
  // running ahead of the clock, made it report its state about four times a second (to the
  // page and to Spotify). Its element therefore shows the speed the page set and the time that
  // is heard (the element's time less the reserve); what the page sets goes to the element.
  const xfMask = el => {
    if (xf.masked.has(el)) return;
    xf.masked.add(el);
    try {
      Object.defineProperty(el, 'playbackRate', {
        configurable: true,
        get() { return xf.ours && el === xf.el ? xf.pageRate : realRate(el); },
        set(v) {
          // The page re-applies its speed every few seconds: the same value changes nothing for it,
          // so the reserve's speed stays. Another value (a podcast speed) is the page's to set.
          if (el === xf.el && xf.ours && Number(v) === xf.pageRate) return;
          xf.pageRate = Number(v);
          setRealRate(el, v);
          if (el === xf.el) xfPageRate(el);
        },
      });
      Object.defineProperty(el, 'currentTime', {
        configurable: true,
        get() {
          const t = realTime(el);
          if (el !== xf.el) return t;
          // The last moments show the element's own time: the page must see its song end.
          if (isFinite(el.duration) && el.duration - t < XF_END_REAL_S) return t;
          return Math.max(0, t - heardLag());
        },
        set(v) { TIME.set.call(el, v); },
      });
    } catch (e) { xfNote('unsupported', { why: 'element cannot be masked: ' + String(e && e.message || e).slice(0, 100) }); }
  };

  const xfNote = (event, extra) => post(Object.assign({ type: 'xfade', event }, extra || {}));
  const r1 = x => Math.round(x * 10) / 10;

  // Content seconds left in the song the element plays, or null when the element's timeline is
  // not the song's (then nothing is built: the reserve would be planned on a wrong end).
  const xfRemaining = el => {
    const d = el.duration;
    if (!isFinite(d) || d < 5 || !xf.durationMs || Math.abs(d * 1000 - xf.durationMs) > 3000) return null;
    return Math.max(0, d - realTime(el));
  };

  // Our speed factor on the stream written from now on (1: none; a page-chosen speed is not ours).
  const xfMark = k => {
    const now = boost.ctx.currentTime;
    const last = xf.segs[xf.segs.length - 1];
    if (last.k === k) return;
    if (last.at >= now) last.k = k; else xf.segs.push({ at: now, k });
  };

  // Each path's delay as scheduled here (dm: the events from the last cut on). A delay is never
  // read back from the AudioParam: its value is what the audio thread rendered last, so right
  // after a change it is still the old one. Read that way, a flush or the new song's delay 0
  // was written back over at once, and the reserve stayed at its old length.
  const dAt = (path, t) => {
    const m = path.dm;
    let i = m.length - 1;
    while (i > 0 && m[i].t > t) i--;
    const a = m[i], b = m[i + 1];
    return b && b.ramp ? a.v + (b.v - a.v) * Math.max(0, t - a.t) / (b.t - a.t) : a.v;
  };
  // Cuts are made at the context's current time, which only moves on: later reads need nothing before.
  const dCut = (path, v, t) => {
    path.delay.delayTime.cancelScheduledValues(t);
    path.delay.delayTime.setValueAtTime(v, t);
    path.dm = [{ t, v }];
  };
  const dRamp = (path, v, t) => {
    path.delay.delayTime.linearRampToValueAtTime(v, t);
    path.dm.push({ t, v, ramp: true });
  };

  // The current path's delay from now on, from the speeds the stream was written at (see above).
  const xfLay = () => {
    const path = boost.paths[xf.cur], now = boost.ctx.currentTime;
    let t = now, d = dAt(path, now), s = now - d;
    dCut(path, d, now);
    while (xf.segs.length > 1 && xf.segs[1].at <= s) xf.segs.shift();
    for (let i = 0; i < xf.segs.length; i++) {
      const k = xf.segs[i].k, next = xf.segs[i + 1];
      if (!next) {
        // Still being written at this speed: read on at 1/k until the delay's limit.
        if (k > 1) {
          const slope = 1 - 1 / k, H = Math.max(0, (XF_MAX_S + 3 - d) / slope);
          if (H > 0) dRamp(path, d + slope * H, t + H);
        }
        return;
      }
      t += (next.at - s) * k;
      s = next.at;
      d = t - s;
      dRamp(path, d, t);
    }
  };

  // Seconds of song between what the page plays and what is heard.
  const xfReserve = () => {
    if (!boost) return 0;
    const now = boost.ctx.currentTime, s = now - dAt(boost.paths[xf.cur], now);
    let c = 0;
    for (let i = 0; i < xf.segs.length; i++) {
      const a = Math.max(s, xf.segs[i].at), b = i + 1 < xf.segs.length ? xf.segs[i + 1].at : now;
      if (b > a) c += (b - a) * xf.segs[i].k;
    }
    return c;
  };

  // Only a speed set here is ever put back: a podcast speed chosen in the page stays.
  const xfRate = k => {
    const el = xf.el;
    if (!el) return;
    if (k === 1) {
      if (xf.ours) { setRealRate(el, xf.pageRate); xf.ours = false; }
      if (xf.pitch !== null) { el.preservesPitch = xf.pitch; xf.pitch = null; }
      xf.k = realRate(el);
    } else {
      if (xf.pitch === null) xf.pitch = el.preservesPitch;
      el.preservesPitch = false;
      if (!xf.ours) xf.pageRate = realRate(el);
      setRealRate(el, k);
      xf.k = k;
      xf.ours = true;
      xf.sets++;
    }
    xfMark(xf.ours ? k : 1);
    xfLay();
  };

  // The page set the speed itself (a new source starts at 1, a podcast speed): ours is over.
  const xfPageRate = el => {
    if (!boost) return;
    if (xf.ours) {
      xf.resets++;
      if (xf.resets <= XF_MAX_RESETS) xfNote('reset', { by: 'page', speed: realRate(el), count: xf.resets });
    }
    xf.ours = false;
    xf.k = realRate(el);
    xfMark(1);
    xfLay();
  };

  // The reserve dropped: the sound jumps to where the page is.
  const xfFlush = why => {
    if (!boost) return;
    const had = xfReserve(), t = boost.ctx.currentTime;
    // Noted even with no reserve of its own: it also ends the old song's tail under the new one.
    const during = performance.now() < xf.overlapUntil;
    xf.overlapUntil = 0;
    boost.paths.forEach((path, i) => {
      dCut(path, 0, t);
      path.g.gain.cancelScheduledValues(t);
      path.g.gain.setValueAtTime(i === xf.cur ? 1 : 0, t);
    });
    xf.segs = [{ at: t, k: 1 }];
    xf.planned = null;
    xfRate(1);
    if (had > 0.05 || during) xfNote('flush', Object.assign({ why, reserve: r1(had), el: xf.el && xf.el.__mspId },
      during ? { during: 'overlap', after: r1((performance.now() - xf.switchAt) / 1000) } : {}));
  };

  // The song ended by itself: its path plays its last seconds and fades out while the other
  // path fades the next song in.
  const xfOverlap = () => {
    const old = xf.cur, L = xfReserve();
    xfRate(1);
    const facts = { target: xf.seconds, speedSets: xf.sets, pageResets: xf.resets };
    // Nothing to cross: the reserve plays out on the same path and the next song follows it.
    if (xf.seconds === 0 || L < 0.3) { xfNote('end', Object.assign({ reserve: r1(L) }, facts)); return; }
    const n = 64, fadeOut = new Float32Array(n), fadeIn = new Float32Array(n);
    for (let i = 0; i < n; i++) {
      const x = i / (n - 1);
      fadeOut[i] = Math.cos(x * Math.PI / 2);
      fadeIn[i] = Math.sin(x * Math.PI / 2);
    }
    const t = boost.ctx.currentTime, po = boost.paths[old], pn = boost.paths[1 - old];
    // The new path is silent here: its delay can jump to 0, the new song's start.
    dCut(pn, 0, t);
    po.g.gain.cancelScheduledValues(t);
    pn.g.gain.cancelScheduledValues(t);
    po.g.gain.setValueCurveAtTime(fadeOut, t, L);
    pn.g.gain.setValueCurveAtTime(fadeIn, t, L);
    // The old path keeps the delay laid out above until its song has played out.
    xf.cur = 1 - old;
    xf.segs = [{ at: t, k: 1 }];
    xf.planned = null;
    xf.overlapUntil = performance.now() + L * 1000;
    xfNote('overlap', Object.assign({ seconds: r1(L), delayOld: r1(dAt(po, t)), el: xf.el && xf.el.__mspId }, facts));
    xfProbe(po, pn, L);
  };

  // RMS level in dBFS of what an analyser sees now (-99: silence).
  const dbOf = an => {
    const buf = new Float32Array(an.fftSize);
    an.getFloatTimeDomainData(buf);
    let sum = 0;
    for (const v of buf) sum += v * v;
    const rms = Math.sqrt(sum / buf.length);
    return rms > 1e-5 ? Math.round(20 * Math.log10(rms)) : -99;
  };

  // Diagnosis: what the overlap sounds like, a few times while it runs. old/new: each path
  // before its fade (the song it carries), gOld/gNew: the fade there, heard: after the limiter.
  const xfProbe = (po, pn, L) => {
    xf.probes.forEach(clearTimeout);
    xf.probes = [0.5, L / 2, L - 1.5].filter(x => x > 0 && x < L).map(x => setTimeout(() => {
      try {
        const k = x / L;
        xfNote('level', { at: r1(x), old: dbOf(po.an), new: dbOf(pn.an), gOld: r1(Math.cos(k * Math.PI / 2)),
          gNew: r1(Math.sin(k * Math.PI / 2)), heard: dbOf(boost.heard), el: xf.el && xf.el.__mspId, ctx: boost.ctx.state });
      } catch (e) { /* no analyser */ }
    }, x * 1000));
  };

  // Every timeupdate of the player's element: builds the reserve towards xf.seconds by the
  // song's end, at the lowest speed that gets there (at most XF_K_MAX).
  const xfTick = () => {
    const el = xf.el;
    if (!boost || !el || !boost.routed.has(el) || el.paused) return;
    // The page changed the speed itself (a new source starts at 1, a podcast speed).
    if (Math.abs(realRate(el) - xf.k) > 1e-6) xfPageRate(el);
    const rem = xfRemaining(el);
    if (rem !== null) xf.lastRem = rem;
    if (xf.resets >= XF_MAX_RESETS && xf.noted !== 'resets') { xf.noted = 'resets'; xfNote('unsupported', { why: 'the page keeps resetting the speed' }); }
    if (xf.seconds > 0 && rem === null && xf.noted !== 'timeline') { xf.noted = 'timeline'; xfNote('unsupported', { why: 'element timeline is not the song', duration: el.duration, songMs: xf.durationMs }); }
    const music = xf.kind === 'track' && (xf.speed || 1) === 1 && !window.__mspMuted && xf.resets < XF_MAX_RESETS;
    const target = music && rem !== null ? xf.seconds : 0;
    const C = xfReserve();
    // Reserve C grows by (k - 1) seconds per second, for rem / k more seconds: k = rem / (rem - (N - C)).
    const need = target - C;
    let k = need <= 0.02 ? 1 : (rem > need ? rem / (rem - need) : XF_K_MAX);
    k = Math.min(XF_K_MAX, Math.max(1, k));
    // Few speed changes: each is a step the media pipeline has to follow.
    if (k === 1 ? !xf.ours : (xf.ours && Math.abs(k - xf.k) < 0.01)) return;
    xfRate(k);
    const key = xf.durationMs + ':' + Math.round(el.duration);
    if (k > 1 && xf.planned !== key) {
      xf.planned = key;
      xfNote('build', { speed: Math.round(k * 1000) / 1000, target, reserve: r1(C), left: Math.round(rem), el: el.__mspId });
    }
  };

  const xfAtEnd = el => el.ended || xf.lastRem <= XF_NATURAL_S || performance.now() - xf.switchAt < 2000 || performance.now() < xf.endingUntil;

  // A new source on the element: the song ended by itself, or was skipped.
  const xfSwitch = () => {
    if (!boost || performance.now() - xf.switchAt < 2000) return;
    xf.switchAt = performance.now();
    const ending = xf.switchAt < xf.endingUntil;
    xf.endingUntil = 0;
    if (xf.lastRem <= XF_NATURAL_S || ending) xfOverlap(); else xfFlush('skip');
    xf.lastRem = Infinity;
    xf.resets = 0;
    xf.sets = 0;
    xf.noted = '';
  };

  const xfWatch = el => {
    xfMask(el);
    const on = (type, fn) => el.addEventListener(type, () => { if (el === xf.el && boost) fn(el); });
    on('timeupdate', xfTick);
    on('ended', xfSwitch);
    on('emptied', xfSwitch);
    on('loadstart', xfSwitch);
    on('seeking', () => { if (performance.now() - xf.switchAt > 2000) xfFlush('seek'); });
    on('pause', () => { if (!xfAtEnd(el)) boost.ctx.suspend().catch(() => {}); });
    on('play', () => boost.ctx.resume().catch(() => {}));
    on('playing', () => boost.ctx.resume().catch(() => {}));
  };

  // How far what is heard lags the element, in seconds (0 while a new song fades in over the old one).
  const heardLag = () => boost ? xfReserve() : 0;

  // A ramp heard as even: the gain follows a cubic curve (the usual "audio taper", the one the
  // page applies to its own volume when _cubicVolume is set), so equal times give equal steps
  // to the ear. A linear or stepped ramp was heard as a jump: most of it in the first moments.
  // Levels go through setLevel (after the limiter while boosted). Ticks every 16 ms; each sets the level the clock is at (a late tick never stretches it).
  // fromK/toK: positions on that curve (0 = silent, 1 = the player's own level, read at every
  // tick so a volume change meanwhile is kept). -> a function that starts it.
  const ramp = (el, player, fromK, toK, ms, done) => () => {
    cancelFade();
    const t0 = performance.now();
    const tick = () => {
      const k = Math.min(1, (performance.now() - t0) / ms);
      const p = fromK + (toK - fromK) * k;
      setLevel(el, player, ownLevel(player) * p * p * p);
      if (k < 1) fadeTimers.push(setTimeout(tick, 16));
      else if (done) done();
    };
    tick();
  };
  // Where the element stands on that curve now (with the graph's own fade, see setLevel).
  const curveAt = (el, player) => {
    const own = ownLevel(player);
    return own > 0 ? Math.min(1, Math.cbrt(Math.max(0, levelOf(el)) / own)) : 1;
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
  // `enable`/`disable` sit on the prototype and `enabled` is a plain field, so the instance is
  // shadowed here: after this, the page cannot arm its own managers again for the life of the page.
  // This does not stop audio ads: Spotify's server puts those in the queue itself (measured on
  // 20/10, desktop, free account: one ad after ~17 min with this latch on, as without it). Those
  // are left out of the queue by the host (AdBlock.withoutAds). The "Test ad" once taken for an
  // ad that came through was tools/playback_test.py's own fake.
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
   * Asks the in-stream ad manager to end its break. Kept for diagnosis only: on a real ad whose
   * audio was refused it did nothing (20/10), the server holds the ad ("ad_disallow").
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
        if (a.on) el.volume = SILENT; else restoreOwn(el, player);
      }
      return els.size;
    },

    // The page player's own media element now: what is really heard, whatever the state says.
    // active: this page is Spotify Connect's active device (else the state is another device's).
    // level: the element's volume (0..1), which local fades move; own: the level the page
    // player itself wants there. playerVolume: what the page player itself is set to, read
    // here too so the host's periodic volume check costs no second call.
    // lag: seconds what is heard lags the element (the crossfade's reserve).
    // -> {paused, muted, volume, level, own, time, readyState, ended, playerVolume, active, lag} | null (no element yet)
    'media.state': async () => {
      const player = await localPlayer();
      const el = player && player._player;
      if (!(el instanceof HTMLMediaElement)) return null;
      let playerVolume = null;
      try { playerVolume = await need().playback.getVolume(); } catch (e) { /* unknown */ }
      return { paused: el.paused, muted: el.muted, volume: el.volume, level: levelOf(el), own: ownLevel(player), time: el.currentTime, readyState: el.readyState, networkState: el.networkState, error: el.error ? el.error.code + ':' + String(el.error.message || '').slice(0, 80) : null, ended: el.ended, playerVolume, active: await activeDevice(), lag: Math.round(heardLag() * 100) / 100 };
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
      if (a.restore) restoreOwn(el, player); else setLevel(el, player, Number(a.level) || 0);
      return levelOf(el);
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
      const set = v => setLevel(el, player, v);
      // A ramp replaces the one before it (a crossfade's up-ramp must not be pulled back down
      // by steps still pending from its down-ramp).
      cancelFade();
      // Each tick sets the step the clock is at, not the next one in line: a late timer (a busy
      // or throttled page) lands on the right level instead of stretching the ramp.
      const t0 = performance.now();
      const tick = () => {
        const i = Math.floor((performance.now() - t0) / per);
        if (i >= steps.length) { if (a.restore) restoreOwn(el, player); else if (steps.length) set(steps[steps.length - 1]); return; }
        set(steps[i]);
        fadeTimers.push(setTimeout(tick, per - ((performance.now() - t0) % per)));
      };
      tick();
      return true;
    },

    // The app's next command changes songs at this one's end (a song played just before an ad
    // Spotify queued, see the host's playBeforeAd): that switch crosses over as a natural end
    // does instead of dropping the crossfade's reserve. -> true
    'media.ending': async () => { xf.endingUntil = performance.now() + XF_ENDING_MS; return true; },

    // The sound going down (a pause) or up, as one smooth ramp. {to: 0..1, ms} -> true
    'media.ramp': async a => {
      const player = await localPlayer();
      const el = player && player._player;
      if (!(el instanceof HTMLMediaElement)) return null;
      ramp(el, player, curveAt(el, player), Math.min(1, Math.max(0, Number(a.to) || 0)), Math.max(1, Number(a.ms) || 150))();
      return true;
    },

    // The sound rising back when a paused song plays again, as the official app does: silent
    // now, then up along the curve once the element's clock really moves, the moment sound comes
    // out. Not on the state saying "playing", nor on the element's own 'playing' event: after a
    // long pause both came 0.5 to 0.9 s before the first sound (measured), and the whole ramp
    // was spent on silence. Not playing within {wait} ms: the level comes back as it was.
    // {ms, wait, keep?} -> true
    'media.rise': async a => {
      const player = await localPlayer();
      const el = player && player._player;
      if (!(el instanceof HTMLMediaElement)) return null;
      cancelFade();
      // keep: from where the element is now (the end of a crossfade), not from silence.
      const from = a.keep ? curveAt(el, player) : 0;
      if (!a.keep) setLevel(el, player, SILENT);
      const rise = ramp(el, player, from, 1, Math.max(1, Number(a.ms) || 500));
      // Moving: ahead of the last look by a little (a new song's clock starting again from 0 is
      // a jump back, not sound; a seek is a jump, not sound either).
      let prev = el.currentTime;
      const waitSound = () => {
        const t = el.currentTime, step = t - prev;
        prev = t;
        if (!el.paused && step > 0.005 && step < 0.5) rise();
        else fadeTimers.push(setTimeout(waitSound, 16));
      };
      fadeTimers.push(setTimeout(() => { cancelFade(); restoreOwn(el, player); }, Math.max(500, Number(a.wait) || 3000)));
      waitSound();
      return true;
    },

    // The volume boost (see "audio graph"): {level: 1..2} -> {level, routed, state}.
    'media.boost': async a => {
      const level = Math.min(BOOST_MAX, Math.max(1, Number(a.level) || 1));
      if (!boost && level <= 1) return { level, routed: 0, state: 'off' };
      ensureGraph();
      boost.level = level;
      boost.gain.gain.value = window.__mspMuted ? 0 : level;
      boost.limiter.threshold.value = level > 1 ? -1 : 0;
      boost.limiter.ratio.value = level > 1 ? 20 : 1;
      await boostRoute();
      return { level, routed: boost.count, state: boost.ctx.state };
    },

    // The crossfade (see "crossfade"): {seconds: 0..12} -> {seconds, routed, state}.
    'media.crossfade': async a => {
      xf.seconds = Math.min(XF_MAX_S, Math.max(0, Math.round(Number(a.seconds) || 0)));
      if (!boost && xf.seconds === 0) return { seconds: 0, routed: 0, state: 'off' };
      ensureGraph();
      await boostRoute();
      xf.planned = null;
      xfTick();
      return { seconds: xf.seconds, routed: boost.count, state: boost.ctx.state };
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

  // Inside one of the app's own commands (see traceSkips).
  let calling = 0;
  const call = async (id, method, args) => {
    try {
      const fn = commands[method];
      if (!fn) throw new Error(`unknown method ${method}`);
      let pending;
      calling++;
      try { pending = fn(args || {}); } finally { calling--; }
      const value = await pending;
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
    traceSkips();
  };

  // Diagnosis (read-only): who asks the page for another song. A tester's song changed with no
  // command from the app (the clip going full screen); the caller's stack, a key or a media
  // session action says where it came from.
  const stackTop = () => String(new Error().stack || '').split('\n').slice(2, 8).map(l => l.trim()).join(' < ').slice(0, 400);
  const traceSkips = () => {
    try {
      const p = services.player;
      for (const name of ['skipToNext', 'skipToPrevious', 'seekTo']) {
        const own = p[name];
        if (typeof own !== 'function' || own.__mspTraced) continue;
        const traced = function (...args) {
          if (!calling) post({ type: 'skip', by: name, stack: stackTop() });
          return own.apply(this, args);
        };
        traced.__mspTraced = true;
        p[name] = traced;
      }
    } catch (e) { /* left as it is */ }
    try {
      const ms = navigator.mediaSession;
      if (ms && !ms.__mspTraced) {
        const set = ms.setActionHandler.bind(ms);
        ms.setActionHandler = (action, handler) => set(action, handler && ((...a) => {
          post({ type: 'skip', by: 'mediaSession.' + action });
          return handler(...a);
        }));
        ms.__mspTraced = true;
      }
    } catch (e) { /* no media session */ }
    window.addEventListener('keydown', e => {
      if (/Arrow|Media|Key[NPJL]/.test(e.code)) post({ type: 'skip', by: 'key ' + e.code + (e.ctrlKey ? '+ctrl' : '') + (e.shiftKey ? '+shift' : '') });
    }, true);
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

  // Widevine, which every Spotify stream is encrypted with: a WebView without a working one (an
  // outdated or stripped Android System WebView, a device without a licence) plays nothing, which
  // looks like "no sound", "refused" or "stuck at 0:00". Asked once per page, from the page's own
  // EME; an empty robustness is the weakest level every Widevine device has (L3).
  // Only on the host's request after a play failed ("drm.probe"), never at boot: asked while the
  // player sets up its own EME (2.0.2-beta.1), the page never registered its playback device.
  let drm = null;
  let drmAsked = false;
  const probeDrm = () => {
    if (drmAsked) return;
    drmAsked = true;
    const report = (ok, error) => { drm = { type: 'drm', ok, error: error || '' }; post(drm); };
    try {
      if (!navigator.requestMediaKeySystemAccess) return report(false, 'no EME in this WebView');
      navigator.requestMediaKeySystemAccess('com.widevine.alpha', [{
        initDataTypes: ['cenc'],
        audioCapabilities: [{ contentType: 'audio/mp4; codecs="mp4a.40.2"' }],
      }]).then(() => report(true), e => report(false, String((e && e.message) || e).slice(0, 200)));
    } catch (e) { report(false, String((e && e.message) || e).slice(0, 200)); }
  };
  commands['drm.probe'] = () => { probeDrm(); return drm; };

  const announce = () => {
    if (!services) return;
    post({ type: 'ready', version: VERSION });
    if (drm) post(drm);
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
