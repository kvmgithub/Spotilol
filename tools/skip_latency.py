#!/usr/bin/env python3
"""Time from a "next" media key to the next song's sound on the phone (debug build).

usage: tools/skip_latency.py [--n 5] [--wait 6]
The key press and the page's first audio frame are both timed on the phone's clock.
"""
import argparse, json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import playback_test as t

WATCH = """(() => {
  const sdk = (() => { const r = __mspPlat().getRegistry(); for (const k of r._map.keys()) if (k && k.description === 'PlayerSDK') return r.resolve(k); })();
  window.__mspLat = null;
  const pl = sdk.harmony._streamer._playerPromise;
  pl.then(p => {
    const from = window.Myspoti && p._currentContent && p._currentContent._track && p._currentContent._track.uri;
    const started = Date.now();
    const tick = () => {
      const el = p._player;
      if (el && !el.paused && el.currentTime > 0.05 && el.currentTime < 2.5 && el.__mspMark !== undefined && el.currentTime !== el.__mspMark) { window.__mspLat = Date.now(); return; }
      if (el) el.__mspMark = el.currentTime < 2.5 ? el.currentTime : undefined;
      if (Date.now() - started < 8000) setTimeout(tick, 15);
    };
    // Wait for the current song to go away first (its element empties).
    const wait = () => { const el = p._player; if (el && el.currentTime < 0.05) tick(); else if (Date.now() - started < 8000) setTimeout(wait, 10); };
    wait();
  });
  return true;
})()"""

PLAT = """window.__mspPlat = () => { const host = document.getElementById('main') || document.body;
  for (const el of [host, ...host.querySelectorAll('*')]) { const key = Object.keys(el).find(k => k.startsWith('__reactContainer$')); if (!key) continue;
    const stack = [el[key]]; while (stack.length) { const f = stack.pop(); const v = f.memoizedProps && f.memoizedProps.value;
      if (v && typeof v === 'object' && typeof v.getRegistry === 'function') return v; if (f.sibling) stack.push(f.sibling); if (f.child) stack.push(f.child); } } }; true"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=5)
    ap.add_argument("--wait", type=float, default=6)
    a = ap.parse_args()
    t.evaluate(PLAT)
    out = []
    for _ in range(a.n):
        t.evaluate(WATCH)
        pressed = int(t.adb("shell", "date +%s%3N; input keyevent 87").splitlines()[0])
        time.sleep(a.wait)
        heard = t.evaluate("window.__mspLat")
        ms = heard - pressed if heard else None
        out.append(ms)
        print("next ->", f"{ms} ms" if ms is not None else "no sound seen")
    ok = [m for m in out if m is not None]
    if ok:
        print(f"median {sorted(ok)[len(ok) // 2]} ms, min {min(ok)}, max {max(ok)}")


if __name__ == "__main__":
    main()
