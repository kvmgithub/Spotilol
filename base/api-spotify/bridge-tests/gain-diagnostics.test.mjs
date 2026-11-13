// The bridge's gain diagnostics, run in the real player-bridge.js without a browser or an account:
// a stand-in Web Player (service registry, player, media elements) and a clock that moves only when
// the test says so. Run: node --test base/api-spotify/bridge-tests/gain-diagnostics.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

// BRIDGE_SOURCE: another copy of the bridge to run the same tests against (an earlier commit's).
const SOURCE = readFileSync(process.env.BRIDGE_SOURCE || new URL('../src/main/resources/bridge/player-bridge.js', import.meta.url), 'utf8');

/** setTimeout and friends on a clock of the test's. */
class Clock {
  constructor() { this.now = 0; this.seq = 0; this.timers = new Map(); this.created = 0; }
  setTimeout(fn, ms = 0) { const id = ++this.seq; this.created++; this.timers.set(id, { at: this.now + Math.max(0, Number(ms) || 0), fn }); return id; }
  clearTimeout(id) { this.timers.delete(id); }
  setInterval(fn, ms) { const id = ++this.seq; this.created++; this.timers.set(id, { at: this.now + ms, fn, every: ms }); return id; }
  advance(ms) {
    const end = this.now + ms;
    for (;;) {
      let next = null;
      for (const [id, t] of this.timers) if (t.at <= end && (!next || t.at < next[1].at)) next = [id, t];
      if (!next) break;
      const [id, t] = next;
      this.now = t.at;
      if (t.every) t.at += t.every; else this.timers.delete(id);
      t.fn();
    }
    this.now = end;
  }
}

/** An HTMLMediaElement as far as the bridge uses one: volume, muted and its listeners. */
class FakeMedia extends EventTarget {
  constructor(name) {
    super();
    this.name = name; this._volume = 1; this.muted = false; this.paused = false; this.currentTime = 0;
    this.readyState = 4; this.currentSrc = 'blob:x'; this.listeners = {};
  }
  get volume() { return this._volume; }
  set volume(v) {
    this._volume = v;
    // As in a browser: the event comes after the script that set it.
    queueMicrotask(() => this.dispatchEvent(new Event('volumechange')));
  }
  addEventListener(type, fn, opts) {
    (this.listeners[type] ||= new Set()).add(fn);
    super.addEventListener(type, fn, opts);
  }
  removeEventListener(type, fn, opts) {
    if (this.listeners[type]) this.listeners[type].delete(fn);
    super.removeEventListener(type, fn, opts);
  }
  count(type) { return this.listeners[type] ? this.listeners[type].size : 0; }
}

const settle = async () => { for (let i = 0; i < 20; i++) await new Promise(r => setImmediate(r)); };

/** A page with the bridge in it; the player plays [state] on element A. */
const page = async (opts = {}) => {
  // An explicit undefined means a player without the field.
  const playerVolume = 'playerVolume' in opts ? opts.playerVolume : 0.8;
  const clock = new Clock();
  const posted = [];
  const listeners = {};
  let state = { item: { type: 'track', uri: 'spotify:track:A', name: 'A', duration: { milliseconds: 200000 } }, context: { uri: 'spotify:playlist:P' }, isPaused: false, isBuffering: false };
  const elA = new FakeMedia('A');
  const player = { _player: elA, _cubicVolume: true };
  if (playerVolume !== undefined) player._playerVolume = playerVolume;
  const services = {
    PlayerAPI: {
      getState: () => state,
      getQueue: () => ({ current: null, queued: [], nextUp: [] }),
      getEvents: () => ({ addListener: (name, fn) => { listeners[name] = fn; } }),
      skipToNext() {}, skipToPrevious() {}, seekTo() {},
    },
    PlaybackAPI: { getVolume: async () => playerVolume },
    LibraryAPI: {},
    TokenProvider: {},
    PlayerSDK: { harmony: { _streamer: { _playerPromise: opts.noPlayer ? null : Promise.resolve(player) } } },
  };
  const streamer = services.PlayerSDK.harmony._streamer;
  const keys = Object.keys(services).map(name => Symbol(name));
  const platform = {
    getRegistry: () => ({ _map: new Map(keys.map(k => [k, true])), resolve: k => services[k.description] }),
    getGraphQLLoader: () => () => null,
    getHistory: () => ({ replace() {} }),
    version: 'test',
  };
  const main = { '__reactContainer$test': { memoizedProps: { value: platform } }, querySelectorAll: () => [] };
  const window = {
    MyspotiHost: { postMessage: json => posted.push(JSON.parse(json)) },
    addEventListener() {},
    setTimeout: (f, ms) => clock.setTimeout(f, ms),
    clearTimeout: id => clock.clearTimeout(id),
    setInterval: (f, ms) => clock.setInterval(f, ms),
    clearInterval: id => clock.clearTimeout(id),
    performance: { now: () => clock.now },
    document: { getElementById: () => main, body: main, querySelectorAll: () => [], addEventListener() {} },
    location: { href: 'https://open.spotify.com/', origin: 'https://open.spotify.com', pathname: '/preferences' },
    navigator: {},
    HTMLMediaElement: FakeMedia,
    Node: class Node {},
    Window: class Window {},
    URL,
  };
  window.window = window;
  vm.createContext(window);
  vm.runInContext(SOURCE, window);
  await settle();
  const call = async (method, args = {}) => {
    const value = window.Myspoti.call(1, method, args);
    await settle();
    return value;
  };
  const update = async next => { if (next) state = next; listeners.update(); await settle(); };
  const diag = () => posted.filter(m => m.type === 'diag');
  const results = () => posted.filter(m => m.type === 'result');
  return { clock, posted, diag, results, call, update, player, elA, streamer, services, window, state: () => state, setState: s => { state = s; } };
};

const song = (uri, ctx = 'spotify:album:X') => ({ item: { type: 'track', uri, name: uri, duration: { milliseconds: 200000 } }, context: { uri: ctx }, isPaused: false, isBuffering: false });
const arm = (p, tag = 1) => p.call('diag.gain', { on: true, tag });
const playerOf = (el, volume, cubic) => ({ _player: el, _playerVolume: volume, _cubicVolume: cubic });
/** A promise the test resolves when it wants (an SDK search that answers late). */
const deferred = () => { let resolve, reject; const promise = new Promise((r, j) => { resolve = r; reject = j; }); return { promise, resolve, reject }; };
/** Every message the page posted, as text: what could reach the host's log by any path. */
const everything = p => JSON.stringify(p.posted);

test('off by default: no message, no listener on the element, no timer', async () => {
  const p = await page();
  const timers = p.clock.created;
  await p.update(song('spotify:track:B'));
  p.elA.volume = 0.3;
  await settle();
  p.clock.advance(5000);
  assert.equal(p.diag().length, 0);
  assert.equal(p.elA.count('volumechange'), 0);
  assert.equal(p.clock.created, timers, 'no timer started for the diagnostics');
});

test('armed: one message with the tag, the raw player volume and the element, one listener', async () => {
  const p = await page();
  await arm(p, 7);
  assert.equal(p.diag().length, 1);
  const [m] = p.diag();
  assert.equal(m.ev, 'enable');
  assert.equal(m.tag, 7);
  assert.equal(m.uri, 'spotify:track:A');
  assert.equal(m.ctx, 'playlist');
  assert.equal(m.pl, 1);
  assert.equal(m.el, 1);
  assert.equal(m.pv, 0.8);
  assert.equal(m.pvSt, 'ok');
  assert.equal(m.cubic, true);
  assert.equal(m.vol, 1);
  assert.equal(m.muted, false);
  assert.equal(m.faults, 0);
  assert.equal(p.elA.count('volumechange'), 1);
});

test('the tag is left where the relay reads it into each request: set when armed, 0 when off', async () => {
  const p = await page();
  assert.equal(p.window.__mspGainTag, undefined, 'nothing while never armed');
  await arm(p, 3);
  assert.equal(p.window.__mspGainTag, 3);
  await arm(p, 4);
  assert.equal(p.window.__mspGainTag, 4);
  await p.call('diag.gain', { on: false });
  assert.equal(p.window.__mspGainTag, 0);
});

test('without a tag the page is not armed', async () => {
  const p = await page();
  await p.call('diag.gain', { on: true });
  await p.call('diag.gain', { on: true, tag: 'x' });
  assert.equal(p.diag().length, 0);
  assert.equal(p.elA.count('volumechange'), 0);
});

test('armed again with the same tag (a resume): no second listener, no second message', async () => {
  const p = await page();
  await arm(p, 3);
  await arm(p, 3);
  assert.equal(p.elA.count('volumechange'), 1);
  assert.equal(p.diag().length, 1);
});

test('armed with a new tag: what waited for the old one is dropped, one listener, the new tag from then on', async () => {
  const p = await page();
  await arm(p, 1);
  for (let i = 0; i < 4; i++) { p.elA.volume = (30 + i) / 100; await settle(); }   // one waits
  await arm(p, 2);
  p.clock.advance(3000);
  await settle();
  const after = p.diag().filter(m => m.tag === 1).length;
  assert.equal(after, 2, 'only what went out before the new arming');
  assert.deepEqual(p.diag().filter(m => m.tag === 2).map(m => m.ev), ['enable']);
  assert.equal(p.elA.count('volumechange'), 1);
});

test('a volume change is told, with the level the bridge itself wrote', async () => {
  const p = await page();
  await arm(p);
  p.clock.advance(1000);
  await p.call('media.volume', { level: 0.25 });
  const m = p.diag().at(-1);
  assert.equal(m.ev, 'volume');
  assert.equal(m.vol, 0.25);
  assert.equal(m.wrote, 'level');
  assert.equal(m.wroteMatch, true);
  assert.equal(p.elA.volume, 0.25, 'the bridge set what it was asked, nothing else');
});

test('a mute for an ad is marked as the bridge\'s own', async () => {
  const p = await page();
  await arm(p);
  p.clock.advance(1000);
  await p.call('media.mute', { on: true });
  const m = p.diag().at(-1);
  assert.equal(m.wrote, 'mute');
  assert.equal(m.adMute, true);
  assert.equal(m.vol, 0.001);
});

test('a change of song, then of context under the same song, is told with the page\'s own context', async () => {
  const p = await page();
  await arm(p);
  p.clock.advance(1000);
  await p.update(song('spotify:track:B', 'spotify:playlist:P'));
  assert.deepEqual([p.diag().at(-1).ev, p.diag().at(-1).uri, p.diag().at(-1).ctx], ['track', 'spotify:track:B', 'playlist']);
  p.clock.advance(1000);
  await p.update(song('spotify:track:B', 'spotify:album:X'));
  assert.deepEqual([p.diag().at(-1).ev, p.diag().at(-1).ctx], ['context', 'album']);
  p.clock.advance(1000);
  p.elA.volume = 0.6;
  await settle();
  assert.equal(p.diag().at(-1).ctx, 'album', 'later lines keep the new context');
});

test('a new SDK player is followed: its own values, its own element, the old listener let go', async () => {
  const p = await page();
  await arm(p);
  p.clock.advance(1000);
  const elB = new FakeMedia('B');
  elB.volume = 0.25;
  await settle();
  p.streamer._playerPromise = Promise.resolve(playerOf(elB, 0.3, false));
  await p.update(song('spotify:track:B'));
  p.clock.advance(1000);
  await settle();
  const told = p.diag().filter(m => m.uri === 'spotify:track:B');
  const last = told.at(-1);
  assert.equal(last.ev, 'player');
  assert.deepEqual([last.pl, last.el, last.pv, last.vol, last.cubic], [2, 2, 0.3, 0.25, false]);
  assert.ok(told.every(m => !(m.pl === 1 && m.uri === 'spotify:track:B' && m.pv === 0.8)), 'never song B with player A\'s values: ' + JSON.stringify(told));
  assert.equal(p.elA.count('volumechange'), 0);
  assert.equal(elB.count('volumechange'), 1);
});

test('no element: the listener goes, the line says none; A, none, then B', async () => {
  const p = await page();
  await arm(p);
  p.clock.advance(1000);
  p.player._player = null;
  await p.update();
  let m = p.diag().at(-1);
  assert.deepEqual([m.ev, m.el, m.vol], ['element', 0, null]);
  assert.equal(p.elA.count('volumechange'), 0);
  p.clock.advance(1000);
  const elB = new FakeMedia('B');
  p.player._player = elB;
  await p.update();
  m = p.diag().at(-1);
  assert.deepEqual([m.ev, m.el], ['element', 2]);
  assert.equal(elB.count('volumechange'), 1);
});

test('a player that appears after arming, under the same song, is followed from the next state', async () => {
  const p = await page({ noPlayer: true });
  await arm(p);
  assert.deepEqual([p.diag().at(-1).pl, p.diag().at(-1).el, p.diag().at(-1).pvSt], [0, 0, 'absent']);
  p.streamer._playerPromise = Promise.resolve(p.player);
  p.clock.advance(1000);
  await p.update();
  assert.equal(p.elA.count('volumechange'), 1);
  const m = p.diag().at(-1);
  assert.deepEqual([m.ev, m.pl, m.el, m.pv], ['player', 1, 1, 0.8]);
  p.clock.advance(1000);
  p.elA.volume = 0.4;
  await settle();
  assert.deepEqual([p.diag().at(-1).ev, p.diag().at(-1).vol], ['volume', 0.4]);
});

test('a search the SDK moved on from never binds, however late it answers', async () => {
  const p = await page({ noPlayer: true });
  await arm(p);
  const slow = deferred();
  p.streamer._playerPromise = slow.promise;
  await p.update();                                       // looks for the slow one
  const elB = new FakeMedia('B');
  p.streamer._playerPromise = Promise.resolve(playerOf(elB, 0.5, true));
  await p.update();                                       // the SDK moved on
  slow.resolve(p.player);                                 // the old answer comes now
  p.clock.advance(3000);
  await settle();
  assert.equal(p.elA.count('volumechange'), 0, 'the old player\'s element is never followed');
  assert.equal(elB.count('volumechange'), 1);
  assert.ok(p.diag().every(m => m.pv !== 0.8), 'never the old player\'s values: ' + JSON.stringify(p.diag()));
});

test('a search from before a new arming never binds', async () => {
  const p = await page({ noPlayer: true });
  const slow = deferred();
  p.streamer._playerPromise = slow.promise;
  await arm(p, 1);                                        // looks for it under tag 1
  await p.call('diag.gain', { on: false });
  p.streamer._playerPromise = null;
  await arm(p, 2);
  slow.resolve(p.player);
  p.clock.advance(3000);
  await settle();
  assert.equal(p.elA.count('volumechange'), 0);
  assert.ok(p.diag().filter(m => m.tag === 2).every(m => m.el === 0), JSON.stringify(p.diag()));
});

test('a search the SDK replaced before any state never binds; the player it holds now is looked for instead', async () => {
  const p = await page({ noPlayer: true });
  const slow = deferred();
  p.streamer._playerPromise = slow.promise;
  await arm(p);                                           // looks for A
  const elB = new FakeMedia('B');
  elB.volume = 0.25;
  p.streamer._playerPromise = Promise.resolve(playerOf(elB, 0.3, false));   // replaced, no state in between
  slow.resolve(p.player);                                 // A's search answers now
  await settle();
  assert.equal(p.elA.count('volumechange'), 0, 'A is not bound');
  assert.equal(elB.count('volumechange'), 1);
  const m = p.diag().at(-1);
  assert.deepEqual([m.ev, m.pv, m.vol, m.cubic, m.faults], ['enable', 0.3, 0.25, false, 0]);
  assert.ok(p.diag().every(x => x.pv !== 0.8), JSON.stringify(p.diag()));
  p.clock.advance(1000);
  await p.update();                                       // the next state changes nothing
  assert.equal(elB.count('volumechange'), 1);
  assert.equal(p.diag().at(-1), m);
});

test('a failed search of an earlier arming counts nothing under the new tag; a failed current one is a fault code', async () => {
  const p = await page({ noPlayer: true });
  const old = deferred();
  p.streamer._playerPromise = old.promise;
  await arm(p, 1);                                        // looks under tag 1
  await p.call('diag.gain', { on: false });
  p.streamer._playerPromise = Promise.resolve(p.player);
  await arm(p, 2);                                        // a healthy player under tag 2
  old.reject(new Error('spotify:track:RAWMARKER'));
  p.clock.advance(1000);
  p.elA.volume = 0.5;
  await settle();
  const m = p.diag().filter(x => x.tag === 2).at(-1);
  assert.deepEqual([m.ev, m.faults, m.fault], ['volume', 0, null], JSON.stringify(m));
  const failing = deferred();
  p.streamer._playerPromise = failing.promise;
  p.clock.advance(1000);
  await p.update(song('spotify:track:B'));               // looks for the new player
  failing.reject(new Error('spotify:track:RAWMARKER'));
  await settle();
  p.clock.advance(1000);
  await p.update(song('spotify:track:C'));
  const n = p.diag().at(-1);
  assert.deepEqual([n.tag, n.ev, n.faults, n.fault], [2, 'track', 1, 'locate'], JSON.stringify(n));
  assert.ok(!everything(p).includes('RAWMARKER'));
});

test('a failing diagnostic read never costs a state message; the next good reading comes through', async () => {
  const run = async on => {
    const p = await page();
    if (on) await arm(p);
    Object.defineProperty(p.player, '_playerVolume', { configurable: true, get() { throw new Error('diagnostic field unavailable spotify:track:RAWMARKER'); } });
    p.clock.advance(1000);
    // Only a change of song: the page's own level code reads _playerVolume too (ownLevel), and is
    // not what this checks.
    await p.update(song('spotify:track:B'));
    return p;
  };
  const off = await run(false), on = await run(true);
  const regular = p => JSON.stringify(p.posted.filter(m => m.type === 'state' || m.type === 'queue'));
  assert.equal(regular(on), regular(off), 'the same state messages');
  assert.ok(regular(on).includes('spotify:track:B'), 'the state of B went out');
  assert.equal(on.elA.volume, off.elA.volume);
  const bad = on.diag().at(-1);
  assert.equal(bad.pvSt, 'error');
  assert.ok(bad.faults > 0 && bad.fault === 'read');
  Object.defineProperty(on.player, '_playerVolume', { configurable: true, writable: true, value: 0.9 });
  on.clock.advance(1000);
  on.elA.volume = 0.7;
  await settle();
  const good = on.diag().at(-1);
  assert.deepEqual([good.pvSt, good.pv, good.vol], ['ok', 0.9, 0.7]);
  assert.ok(!everything(on).includes('RAWMARKER'), 'no free error text anywhere');
});

test('a failing SDK lookup or element read is a fault code, never an exception', async () => {
  const p = await page();
  await arm(p);
  Object.defineProperty(p.services.PlayerSDK, 'harmony', { configurable: true, get() { throw new Error('spotify:track:RAWMARKER'); } });
  Object.defineProperty(p.player, '_player', { configurable: true, get() { throw new Error('spotify:track:RAWMARKER'); } });
  p.clock.advance(1000);
  await p.update(song('spotify:track:B'));
  assert.ok(p.posted.some(m => m.type === 'state' && m.state.track.uri === 'spotify:track:B'));
  assert.ok(!everything(p).includes('RAWMARKER'));
});

test('arming with a getter that throws: the call answers, without the thrown text', async () => {
  const p = await page();
  Object.defineProperty(p.player, '_playerVolume', { configurable: true, get() { throw new Error('spotify:track:RAWMARKER'); } });
  Object.defineProperty(p.player, '_cubicVolume', { configurable: true, get() { throw new Error('spotify:track:RAWMARKER'); } });
  await arm(p);
  const result = p.results().at(-1);
  assert.equal(result.ok, true);
  assert.equal(p.diag().at(-1).pvSt, 'error');
  assert.ok(!everything(p).includes('RAWMARKER'), everything(p));
});

test('a flood: two messages a second, the rest as one with how many it stands for', async () => {
  const p = await page();
  await arm(p);                                           // the 1st of this second
  for (let i = 0; i < 10; i++) { p.elA.volume = (11 + i) / 100; await settle(); }
  assert.equal(p.diag().length, 2, 'enable and the first change');
  p.clock.advance(1000);
  await settle();
  assert.equal(p.diag().length, 3);
  const last = p.diag().at(-1);
  assert.equal(last.merged, 8, 'ten changes: one told at once, the other nine as one');
  assert.equal(last.vol, 20 / 100, 'read when sent: the newest');
  p.clock.advance(5000);
  assert.equal(p.diag().length, 3);
});

test('turned off with a message waiting: nothing more, the listener is gone', async () => {
  const p = await page();
  await arm(p);
  for (let i = 0; i < 5; i++) { p.elA.volume = 0.3 + i / 100; await settle(); }
  const before = p.diag().length;
  await p.call('diag.gain', { on: false });
  p.clock.advance(3000);
  p.elA.volume = 0.9;
  await p.update(song('spotify:track:C'));
  p.clock.advance(3000);
  assert.equal(p.diag().length, before);
  assert.equal(p.elA.count('volumechange'), 0);
});

test('on or off, the page gets the same state messages and the same levels', async () => {
  const run = async on => {
    const p = await page();
    if (on) await arm(p);
    await p.update(song('spotify:track:B'));
    await p.call('media.volume', { level: 0.4 });
    await p.update(song('spotify:track:C'));
    p.clock.advance(3000);
    await settle();
    return { other: JSON.stringify(p.posted.filter(m => m.type !== 'diag' && m.type !== 'result')), volume: p.elA.volume, muted: p.elA.muted };
  };
  assert.deepEqual(await run(true), await run(false));
});

// The relay's fetch wrapper (ControlRelay.SCRIPT, run at document start), as the Kotlin source has it:
// it reads the bridge's tag into each request it hands the host, so the host knows the asking page.
const RELAY = (() => {
  const kt = readFileSync(new URL('../../host-android/src/main/kotlin/app/myspoti/host/android/ControlRelay.kt', import.meta.url), 'utf8');
  const m = /private val SCRIPT = """([\s\S]*?)"""\.trimIndent\(\)/.exec(kt);
  assert.ok(m, 'the relay script in ControlRelay.kt');
  assert.ok(!/\$[A-Za-z_{]/.test(m[1]), 'a plain raw string, nothing filled in by Kotlin');
  return m[1];
})();

const relayPage = () => {
  const handed = [];
  const handlers = [];
  const window = {
    MyspotiNet: { postMessage: s => handed.push(JSON.parse(s)), addEventListener: (type, fn) => handlers.push(fn) },
    navigator: { userAgent: 'test', languages: ['de'] },
    HTMLMediaElement: class { play() {} },
    fetch: () => { throw new Error('not relayed'); },
    Headers, Response, URL, setTimeout, clearTimeout,
  };
  window.window = window;
  vm.createContext(window);
  vm.runInContext(RELAY, window);
  const answer = (id, status, body) => handlers.forEach(h => h({ data: JSON.stringify({ id, status, headers: '{"content-type":"application/json"}', body }) }));
  return { window, requests: () => handed.filter(m => m.id), answer };
};

test('the relay hands the host each request with the tag of the page that sent it, and the answer back unchanged', async () => {
  const r = relayPage();
  const url = 'https://gew4-spclient.spotify.com/track-playback/v1/devices/DEVICE/state';
  r.window.fetch(url, { method: 'PUT', body: '{"n":1}' });               // not armed
  r.window.__mspGainTag = 5;
  const armed = r.window.fetch(url, { method: 'PUT', body: '{"n":2}' });
  r.window.__mspGainTag = 'x';
  r.window.fetch(url, { method: 'PUT', body: '{"n":3}' });               // not a tag
  Object.defineProperty(r.window, '__mspGainTag', { configurable: true, get() { throw new Error('spotify:track:RAWMARKER'); } });
  r.window.fetch(url, { method: 'PUT', body: '{"n":4}' });               // unreadable: the request still goes
  assert.deepEqual(r.requests().map(m => [JSON.parse(m.body).n, m.gain]), [[1, 0], [2, 5], [3, 0], [4, 0]]);
  assert.ok(!JSON.stringify(r.requests()).includes('RAWMARKER'));
  r.answer(r.requests()[1].id, 200, '{"state_machine":{"tracks":[]}}');
  const res = await armed;
  assert.equal(res.status, 200);
  assert.equal(await res.text(), '{"state_machine":{"tracks":[]}}');
});
