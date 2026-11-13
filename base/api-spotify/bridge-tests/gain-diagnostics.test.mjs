// The bridge's gain diagnostics, run in the real player-bridge.js without a browser or an account:
// a stand-in Web Player (service registry, player, media elements) and a clock that moves only when
// the test says so. Run: node --test base/api-spotify/bridge-tests/gain-diagnostics.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const SOURCE = readFileSync(new URL('../src/main/resources/bridge/player-bridge.js', import.meta.url), 'utf8');

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
const page = async ({ playerVolume = 0.8 } = {}) => {
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
    PlayerSDK: { harmony: { _streamer: { _playerPromise: Promise.resolve(player) } } },
  };
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
  return { clock, posted, diag, call, update, player, elA, window, state: () => state, setState: s => { state = s; } };
};

const song = (uri, ctx = 'spotify:album:X') => ({ item: { type: 'track', uri, name: uri, duration: { milliseconds: 200000 } }, context: { uri: ctx }, isPaused: false, isBuffering: false });

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

test('turned on: one message with the raw player volume and the element, one listener', async () => {
  const p = await page();
  await p.call('diag.gain', { on: true });
  const [m] = p.diag();
  assert.equal(p.diag().length, 1);
  assert.equal(m.ev, 'enable');
  assert.equal(m.uri, 'spotify:track:A');
  assert.equal(m.ctx, 'playlist');
  assert.equal(m.el, 1);
  assert.equal(m.pv, 0.8);
  assert.equal(m.pvSt, 'ok');
  assert.equal(m.cubic, true);
  assert.equal(m.vol, 1);
  assert.equal(m.muted, false);
  assert.equal(m.adMute, false);
  assert.equal(p.elA.count('volumechange'), 1);
});

test('turned on twice (a resume): no second listener, no second message', async () => {
  const p = await page();
  await p.call('diag.gain', { on: true });
  await p.call('diag.gain', { on: true });
  assert.equal(p.elA.count('volumechange'), 1);
  assert.equal(p.diag().length, 1);
});

test('a volume change is told, with the level the bridge itself wrote', async () => {
  const p = await page();
  await p.call('diag.gain', { on: true });
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
  await p.call('diag.gain', { on: true });
  p.clock.advance(1000);
  await p.call('media.mute', { on: true });
  const m = p.diag().at(-1);
  assert.equal(m.wrote, 'mute');
  assert.equal(m.adMute, true);
  assert.equal(m.vol, 0.001);
});

test('a change of song is told with its own uri; a change of element moves the listener', async () => {
  const p = await page();
  await p.call('diag.gain', { on: true });
  p.clock.advance(1000);
  await p.update(song('spotify:track:B'));
  const t = p.diag().at(-1);
  assert.equal(t.ev, 'track');
  assert.equal(t.uri, 'spotify:track:B');
  assert.equal(t.ctx, 'album');
  p.clock.advance(1000);
  const elB = new FakeMedia('B');
  p.player._player = elB;
  await p.update();
  const e = p.diag().at(-1);
  assert.equal(e.ev, 'element');
  assert.equal(e.el, 2);
  assert.equal(p.elA.count('volumechange'), 0, 'the element left is let go');
  assert.equal(elB.count('volumechange'), 1);
});

test('a missing or odd _playerVolume is said, not taken as 1', async () => {
  const p = await page({ playerVolume: undefined });
  await p.call('diag.gain', { on: true });
  const m = p.diag().at(-1);
  assert.equal(m.pv, null);
  assert.equal(m.pvSt, 'absent');
  p.player._playerVolume = 'loud';
  p.clock.advance(1000);
  p.elA.volume = 0.5;
  await settle();
  assert.equal(p.diag().at(-1).pvSt, 'invalid');
});

test('a flood: two messages a second, the rest as one with how many it stands for', async () => {
  const p = await page();
  await p.call('diag.gain', { on: true });            // 1st of this second
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
  await p.call('diag.gain', { on: true });
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
    if (on) await p.call('diag.gain', { on: true });
    await p.update(song('spotify:track:B'));
    await p.call('media.volume', { level: 0.4 });
    await p.update(song('spotify:track:C'));
    p.clock.advance(3000);
    await settle();
    return { other: JSON.stringify(p.posted.filter(m => m.type !== 'diag' && m.type !== 'result')), volume: p.elA.volume, muted: p.elA.muted };
  };
  assert.deepEqual(await run(true), await run(false));
});
