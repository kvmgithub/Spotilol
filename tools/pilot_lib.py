"""Shared helpers of the unattended playback pilot (tools/pilot.py, tools/uitrace_check.py).

Everything is in the PHONE's clock (epoch ms): logcat -v epoch, the page's Date.now().
The host clock is not used for any measurement.
"""
import json, os, re, subprocess, sys, threading, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cdp  # noqa: E402
import playback_test as pt  # noqa: E402

ADB = pt.ADB
PKG = pt.PKG

# The page-side sampler: every 50 ms, every media element the host's play() hook knows about
# (window.__mspMedia) that has been used, plus its media events with exact timestamps.
SAMPLER_JS = r"""(() => {
  if (window.__pilot && window.__pilot.stop) window.__pilot.stop();
  const P = window.__pilot = { buf: [], seen: new WeakSet(), ids: new Map(), n: 0, last: 0 };
  const evs = ['playing','pause','play','waiting','stalled','ended','emptied','loadstart','loadedmetadata','canplay','seeking','seeked','volumechange','error','abort','suspend'];
  const id = el => { let k = P.ids.get(el); if (!k) { k = ++P.n; P.ids.set(el, k); } return k; };
  const used = el => el.readyState > 0 || !el.paused || el.currentTime > 0;
  const hook = el => {
    if (P.seen.has(el)) return; P.seen.add(el);
    evs.forEach(e => el.addEventListener(e, () => el.muted || P.buf.push(['e', Date.now(), e, id(el), +el.currentTime.toFixed(3), +el.volume.toFixed(4), el.paused ? 1 : 0, el.readyState])));
  };
  P.tick = setInterval(() => {
    const now = Date.now();
    const m = window.__mspMedia ? [...window.__mspMedia] : [];
    m.forEach(hook);
    for (const el of m) if (used(el) && !el.muted) P.buf.push(['s', now, id(el), +el.currentTime.toFixed(3), el.paused ? 1 : 0, +el.volume.toFixed(4), el.readyState, el.ended ? 1 : 0, el.muted ? 1 : 0]);
    P.last = now;
  }, 50);
  P.stop = () => { clearInterval(P.tick); };
  P.drain = () => { const b = P.buf; P.buf = []; return b; };
  return 'sampler on';
})()"""

TIMER_CHAIN_JS = r"""(async () => {
  const out = [];
  for (let k = 0; k < 3; k++) {
    const t0 = performance.now();
    await new Promise(res => { let n = 0; const f = () => { if (++n >= 8) return res(); setTimeout(f, 38); }; f(); });
    out.push(Math.round(performance.now() - t0));
    await new Promise(res => setTimeout(res, 300));
  }
  return { vis: document.visibilityState, hidden: document.hidden, chain8x38_ms: out };
})()"""


def adb(*a, timeout=60, check=False):
    r = subprocess.run([ADB, *a], capture_output=True, text=True, timeout=timeout)
    return r.stdout.strip()


def phone_now_ms():
    try:
        return int(adb("shell", "date", "+%s%3N", timeout=10))
    except Exception:
        return 0


class Page:
    """A persistent CDP session on the engine page, reopened when the page goes away."""

    def __init__(self):
        self.s = None
        self.n = 0
        self.lock = threading.Lock()

    def close(self):
        try:
            if self.s:
                self.s.close()
        except Exception:
            pass
        self.s = None

    def eval(self, expr, timeout=20):
        with self.lock:
            for attempt in (0, 1):
                try:
                    if self.s is None:
                        self.s = pt.page_socket()
                    self.n += 1
                    mid = self.n
                    self.s.settimeout(timeout)
                    cdp.ws_send(self.s, json.dumps({"id": mid, "method": "Runtime.evaluate", "params": {
                        "expression": expr, "awaitPromise": True, "returnByValue": True, "timeout": timeout * 1000}}))
                    while True:
                        msg = json.loads(cdp.ws_recv(self.s))
                        if msg.get("id") == mid:
                            r = msg.get("result", {})
                            if "exceptionDetails" in r:
                                raise RuntimeError("page exception: " + str(r["exceptionDetails"].get("exception", {}).get("description", ""))[:200])
                            return r.get("result", {}).get("value")
                except RuntimeError:
                    raise
                except Exception:
                    self.close()
                    if attempt:
                        raise
                    time.sleep(1)


class Sampler(threading.Thread):
    """Drains the page sampler every few seconds into a jsonl file (one array per line)."""

    def __init__(self, path, page=None, every=3.0):
        super().__init__(daemon=True)
        self.path, self.page, self.every = path, page or Page(), every
        self.stop_ev = threading.Event()
        self.errors = []
        self.installs = 0
        self.rows = 0

    def drain(self, f):
        try:
            data = self.page.eval("window.__pilot ? window.__pilot.drain() : null", timeout=15)
            if data is None:
                self.page.eval(SAMPLER_JS, timeout=15)
                self.installs += 1
                f.write(json.dumps(["i", int(time.time() * 1000), self.installs]) + "\n")
                return
            for row in data:
                f.write(json.dumps(row) + "\n")
            self.rows += len(data)
            f.flush()
        except Exception as e:
            self.errors.append(f"{time.strftime('%H:%M:%S')} {str(e)[:120]}")
            self.page.close()

    def run(self):
        with open(self.path, "w") as f:
            try:
                self.page.eval(SAMPLER_JS, timeout=20)  # always a fresh buffer
                self.installs += 1
            except Exception as e:
                self.errors.append(str(e)[:120])
            while not self.stop_ev.wait(self.every):
                self.drain(f)
            self.drain(f)

    def finish(self):
        self.stop_ev.set()
        self.join(30)
        try:
            self.page.eval("window.__pilot && window.__pilot.stop && window.__pilot.stop()", timeout=10)
        except Exception:
            pass
        self.page.close()


class Capture:
    """One scenario's raw material: logcat (epoch) in <base>.log, page samples in <base>.media.jsonl."""

    def __init__(self, outdir, name, sampler=True, every=3.0):
        self.base = os.path.join(outdir, name)
        adb("logcat", "-c")
        self.logf = open(self.base + ".log", "w")
        self.proc = subprocess.Popen([ADB, "logcat", "-v", "epoch", "-s", "Myspoti:*", "MspPlayer:*", "MspTrace:*", "AndroidRuntime:E"],
                                     stdout=self.logf, stderr=subprocess.DEVNULL)
        self.sampler = Sampler(self.base + ".media.jsonl", every=every) if sampler else None
        if self.sampler:
            self.sampler.start()

    def stop(self):
        if self.sampler:
            self.sampler.finish()
        time.sleep(1.0)
        self.proc.kill()
        self.logf.close()
        return self.base

    def licence_429(self):
        n = 0
        with open(self.base + ".log", errors="replace") as f:
            for l in f:
                ll = l.lower()
                if "http 429" in ll and "widevine" in ll:
                    n += 1
        return n


def cmd(c, **extras):
    pt.test(c, **extras)


LINE = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+([VDIWEF])\s+(\S+?)\s*:\s?(.*)$")
TRACE = re.compile(r"t=(\d+) ui_pos=(-?\d+) ui_dur=(-?\d+) ui_play=(\w+) ui_uri=(\S*) ui_cover=(\S*) eng_uri=(\S*) "
                   r"eng_pos=(\S*) eng_paused=(\S*) eng_buf=(\S*) ui_title=(.*) \| eng_title=(.*)$")


def parse_log(path):
    """-> (commands [(ms, name, lineno)], traces [dict], other [(ms, tag, text, lineno)])"""
    cmds, traces, other = [], [], []
    with open(path, errors="replace") as f:
        for no, line in enumerate(f, 1):
            m = LINE.match(line.rstrip("\n"))
            if not m:
                continue
            ms = int(round(float(m[1]) * 1000))
            tag, text = m[3], m[4]
            if tag == "MspTrace":
                t = TRACE.search(text)
                if t:
                    num = lambda s: None if s in ("null", "") else int(float(s))
                    traces.append(dict(ms=ms, no=no, t=int(t[1]), ui_pos=int(t[2]), ui_dur=int(t[3]), ui_play=t[4] == "true",
                                       ui_uri=t[5], ui_cover=t[6], eng_uri=t[7], eng_pos=num(t[8]),
                                       eng_paused={"true": True, "false": False}.get(t[9]), eng_buf=t[10] == "true",
                                       ui_title=t[11], eng_title=t[12]))
            elif tag == "MspPlayer" and text.startswith("test command "):
                cmds.append((ms, text[len("test command "):].strip(), no))
            else:
                other.append((ms, tag, text, no))
    return cmds, traces, other


def load_media(path):
    """-> (samples [(ms, el, ct, paused, vol, rs, ended, muted, lineno)], events [(ms, name, el, ct, vol, paused, rs, lineno)])"""
    samples, events = [], []
    if not os.path.exists(path):
        return samples, events
    epoch = 0  # the sampler is installed again after a page reload: element ids restart at 1
    with open(path) as f:
        for no, line in enumerate(f, 1):
            try:
                r = json.loads(line)
            except ValueError:
                continue
            if r[0] == "i":
                epoch += 1
            elif r[0] == "s":
                r[2] += 100 * epoch
                samples.append(tuple(r[1:]) + (no,))
            elif r[0] == "e":
                r[3] += 100 * epoch
                events.append(tuple(r[1:]) + (no,))
    samples.sort(key=lambda x: x[0])
    events.sort(key=lambda x: x[0])
    return samples, events
