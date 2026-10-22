#!/usr/bin/env python3
"""Analyses what a pilot run captured: the app's UI trace (MspTrace) and the page's media element.

usage: tools/uitrace_check.py BASE [--json]      BASE = path without extension of
       BASE.log (logcat -v epoch) and BASE.media.jsonl (tools/pilot.py's page sampler)

UI trace (what the app SHOWS vs what the engine page reports), counted as anomalies:
  ui_pos_back / eng_pos_back   position going backwards while playing on the same song (> 0.8 s)
  ui_pos_jump / eng_pos_jump   position jumping forward more than 2 s beyond the clock
  ui_eng_pos_diff              |ui_pos - eng_pos| > 1.5 s for > 1 s (same song)
  ui_eng_uri_diff              ui_uri != eng_uri for > 2 s
  ui_eng_title_diff            ui_title != eng_title for > 2 s
  ui_eng_play_diff             ui says playing while the engine is paused (or the reverse) for > 1.5 s
  cover_change_same_uri        the cover changed while the song did not
  cover_same_after_uri_change  (soft) the song changed, the cover did not (same album is legitimate)
  trace_gap                    no trace line for > 1 s (the app's main thread / the logger stalled)
Media element (the page's own audio element, the one that is not muted):
  ct_back                      currentTime going backwards while playing, with no load/seek event around it
  stall                        not paused, readyState >= 3, currentTime frozen for > 1.5 s
  low_volume                   playing with volume < 90 % of the reference level for > 1 s (outside a resume rise)
  silence_gap                  no element advancing for > 0.6 s, outside a pause the user asked for
  resume/pause/next/seek metrics: latency from the app's "test command" log line
Each anomaly is "cmd" when it happens within 3 s of a seek/previous/play/next command (a jump is then
expected) and "spont" otherwise; a run fails on "spont" ones. Times are the phone's epoch ms.
"""
import argparse, json, os, re, sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pilot_lib as L  # noqa: E402

MOVERS = ("seek", "previous", "play", "next", "swipenext")


def near(cmds, ms, names=MOVERS, before=400, after=3000):
    return any(n in names and c - before <= ms <= c + after for c, n, _ in cmds)


def ev(kind, ms, cmds, detail, no=None, no2=None, movers=MOVERS, **extra):
    return dict(kind=kind, ms=ms, scope="cmd" if near(cmds, ms, movers) else "spont", detail=detail, line=no, line2=no2, **extra)


def runs(rows, pred, min_ms):
    out, start = [], None
    for i, r in enumerate(rows):
        if pred(r):
            if start is None:
                start = i
        elif start is not None:
            if rows[i - 1]["ms"] - rows[start]["ms"] >= min_ms:
                out.append((start, i - 1))
            start = None
    if start is not None and rows[-1]["ms"] - rows[start]["ms"] >= min_ms:
        out.append((start, len(rows) - 1))
    return out


def check_ui(traces, cmds):
    E = []
    for i in range(1, len(traces)):
        a, b = traces[i - 1], traces[i]
        dt = b["t"] - a["t"]
        if 1000 < dt <= 20000:  # a longer hole is a new uitrace session (the trace is requested for a number of seconds)
            E.append(ev("trace_gap", b["ms"], cmds, f"{dt} ms without a trace line", b["no"]))
        if a["ui_uri"] == b["ui_uri"] and a["ui_play"] and b["ui_play"] and a["ui_uri"] not in ("", "null"):
            d = b["ui_pos"] - a["ui_pos"]
            if d < -800:
                E.append(ev("ui_pos_back", b["ms"], cmds, f"ui_pos {a['ui_pos']} -> {b['ui_pos']} ({a['ui_title']})", b["no"]))
            elif d > dt + 2000:
                E.append(ev("ui_pos_jump", b["ms"], cmds, f"ui_pos {a['ui_pos']} -> {b['ui_pos']} in {dt} ms", b["no"]))
        if (a["eng_uri"] == b["eng_uri"] and a["eng_paused"] is False and b["eng_paused"] is False
                and a["eng_pos"] is not None and b["eng_pos"] is not None):
            d = b["eng_pos"] - a["eng_pos"]
            if d < -800:
                E.append(ev("eng_pos_back", b["ms"], cmds, f"eng_pos {a['eng_pos']} -> {b['eng_pos']} ({a['eng_title']})", b["no"]))
            elif d > dt + 2000:
                E.append(ev("eng_pos_jump", b["ms"], cmds, f"eng_pos {a['eng_pos']} -> {b['eng_pos']} in {dt} ms", b["no"]))
        if a["ui_uri"] == b["ui_uri"] and a["ui_cover"] != b["ui_cover"] and a["ui_uri"] not in ("", "null"):
            E.append(ev("cover_change_same_uri", b["ms"], cmds, f"{a['ui_cover']} -> {b['ui_cover']} on {b['ui_title']}", b["no"]))
        if a["ui_uri"] != b["ui_uri"] and a["ui_cover"] == b["ui_cover"] and a["ui_uri"] not in ("", "null") and b["ui_uri"] not in ("", "null"):
            E.append(ev("cover_same_after_uri_change", b["ms"], cmds, f"{a['ui_title']} -> {b['ui_title']}, cover {b['ui_cover']}", b["no"], soft=True))
    same = lambda r: r["ui_pos"] is not None and r["eng_pos"] is not None and r["ui_uri"] == r["eng_uri"] and r["ui_uri"] not in ("", "null")
    for s, e in runs(traces, lambda r: same(r) and abs(r["ui_pos"] - r["eng_pos"]) > 1500, 1000):
        E.append(ev("ui_eng_pos_diff", traces[s]["ms"], cmds,
                    f"{traces[e]['ms'] - traces[s]['ms']} ms, ui {traces[s]['ui_pos']} vs eng {traces[s]['eng_pos']} ({traces[s]['ui_title']})", traces[s]["no"], traces[e]["no"]))
    for s, e in runs(traces, lambda r: r["ui_uri"] != r["eng_uri"], 2000):
        E.append(ev("ui_eng_uri_diff", traces[s]["ms"], cmds,
                    f"{traces[e]['ms'] - traces[s]['ms']} ms, ui {traces[s]['ui_uri']} ({traces[s]['ui_title']}) vs eng {traces[s]['eng_uri']} ({traces[s]['eng_title']})", traces[s]["no"], traces[e]["no"]))
    for s, e in runs(traces, lambda r: r["ui_uri"] == r["eng_uri"] and r["ui_title"] != r["eng_title"], 2000):
        E.append(ev("ui_eng_title_diff", traces[s]["ms"], cmds,
                    f"{traces[e]['ms'] - traces[s]['ms']} ms, ui {traces[s]['ui_title']!r} vs eng {traces[s]['eng_title']!r}", traces[s]["no"], traces[e]["no"]))
    for s, e in runs(traces, lambda r: r["eng_paused"] is not None and r["ui_play"] == r["eng_paused"], 1500):
        E.append(ev("ui_eng_play_diff", traces[s]["ms"], cmds,
                    f"{traces[e]['ms'] - traces[s]['ms']} ms, ui_play={traces[s]['ui_play']} eng_paused={traces[s]['eng_paused']}", traces[s]["no"], traces[e]["no"],
                    movers=("pause", "resume", "play", "next", "previous", "swipenext")))
    return E


def by_el(samples):
    d = {}
    for s in samples:
        if s[7]:  # muted: the Canvas video, not the player
            continue
        d.setdefault(s[1], []).append(s)
    return d


def check_media(samples, events, cmds, ref=1.0):
    E = []
    els = by_el(samples)
    loads = lambda el, ms: any(e[2] == el and e[1] in ("loadstart", "emptied", "loadedmetadata", "seeking", "seeked") and ms - 1500 <= e[0] <= ms + 800 for e in events)
    for el, rows in els.items():
        frozen_from = None
        for i in range(1, len(rows)):
            a, b = rows[i - 1], rows[i]
            if not a[3] and not b[3] and b[2] < a[2] - 0.3 and not loads(el, b[0]):
                E.append(ev("ct_back", b[0], cmds, f"el{el} currentTime {a[2]} -> {b[2]} with no load/seek event", b[8]))
            adv = b[2] - a[2] > 0.001
            if not b[3] and b[5] >= 3 and not adv and not loads(el, b[0]):
                frozen_from = frozen_from if frozen_from is not None else i - 1
            else:
                if frozen_from is not None and rows[i - 1][0] - rows[frozen_from][0] > 1500:
                    E.append(ev("stall", rows[frozen_from][0], cmds, f"el{el} ct frozen {rows[i-1][0]-rows[frozen_from][0]} ms at {rows[frozen_from][2]}", rows[frozen_from][8]))
                frozen_from = None
    # audible spans: some unmuted element not paused and advancing
    spans = []
    for el, rows in els.items():
        for i in range(1, len(rows)):
            a, b = rows[i - 1], rows[i]
            if not a[3] and not b[3] and b[2] - a[2] > 0.5 * (b[0] - a[0]) / 1000 and b[0] - a[0] < 1500:
                spans.append((a[0], b[0], el, b[4], b[8]))
    spans.sort()
    merged = []
    for s in spans:
        if merged and s[0] <= merged[-1][1] + 120:
            if s[1] > merged[-1][1]:
                merged[-1] = (merged[-1][0], s[1], merged[-1][2], merged[-1][3], merged[-1][4])
        else:
            merged.append(s)
    gaps = []
    for a, b in zip(merged, merged[1:]):
        g = b[0] - a[1]
        if g > 600:
            asked = any(n in ("pause", "play", "resume", "next", "previous", "swipenext", "seek") and a[1] - 600 <= c <= b[0] + 200 for c, n, _ in cmds)
            gaps.append(dict(from_ms=a[1], to_ms=b[0], gap_ms=g, asked=asked, line=a[4], line2=b[4]))
            if not asked:
                E.append(ev("silence_gap", a[1], cmds, f"{g} ms without audio progress (el{a[2]} -> el{b[2]})", a[4], b[4]))
    # low volume while advancing, outside the rise after an audio start
    for el, rows in els.items():
        low_from = None
        for i in range(1, len(rows)):
            a, b = rows[i - 1], rows[i]
            adv = not a[3] and not b[3] and b[2] - a[2] > 0.001
            starts = [s for s in merged if s[2] == el and s[0] <= b[0]]
            lowv = adv and b[4] < 0.9 * ref
            if lowv:
                low_from = low_from if low_from is not None else i
            else:
                if low_from is not None:
                    dur = rows[i - 1][0] - rows[low_from][0]
                    if dur > 1000:
                        vmin = min(r[4] for r in rows[low_from:i])
                        E.append(ev("low_volume", rows[low_from][0], cmds, f"el{el} vol<{0.9*ref:.2f} for {dur} ms (min {vmin})", rows[low_from][8], rows[i - 1][8],
                                    movers=("resume", "play", "next", "swipenext", "previous", "pause", "seek")))
                low_from = None
    return E, gaps, merged


def player_el(samples, ms):
    rows = [s for s in samples if not s[7] and s[0] <= ms]
    if not rows:
        return None
    # the element sampled last before ms, among those that were advancing in the previous minute
    return rows[-1][1]


def command_metrics(samples, events, cmds, traces, ref_default=1.0):
    """Latency per command, from the 'test command' log line (receiver time) to the first effect."""
    out = []
    S = [s for s in samples if not s[7]]
    prev_level = ref_default
    for idx, (ms, name, no) in enumerate(cmds):
        nxt = cmds[idx + 1][0] if idx + 1 < len(cmds) else ms + 20000
        horizon = min(nxt, ms + 12000)
        m = dict(cmd=name, ms=ms, line=no)
        before = [s for s in S if s[0] <= ms]
        if before:
            last = before[-1]
            m["vol_before"] = last[4]
            m["ct_before"] = last[2]
            m["el_before"] = last[1]
        if name == "pause":
            if before and not before[-1][3]:
                prev_level = before[-1][4]
            pe = next((e for e in events if ms <= e[0] <= ms + 3000 and e[1] == "pause"), None)
            m["to_pause_event_ms"] = pe[0] - ms if pe else None
            m["vol_at_pause_event"] = pe[4] if pe else None
            m["ref_level"] = prev_level
        elif name in ("resume", "play"):
            ref = prev_level
            m["ref_level"] = ref
            win = [s for s in S if ms <= s[0] <= horizon]
            pl = next((e for e in events if e[0] >= ms and e[1] == "play"), None)
            pg = next((e for e in events if e[0] >= ms and e[1] == "playing"), None)
            m["to_play_call_ms"] = pl[0] - ms if pl else None
            m["to_playing_event_ms"] = pg[0] - ms if pg else None
            adv = None
            lastct = {}
            for s in win:
                prev = lastct.get(s[1])
                lastct[s[1]] = s[2]
                base = m.get("ct_before") if s[1] == m.get("el_before") else None
                if not s[3] and prev is not None and s[2] > prev + 0.03 and (base is None or name == "play" or s[2] > base + 0.03):
                    adv = s
                    break
            m["to_audio_ms"] = adv[0] - ms if adv else None
            first = next((x for x in win if not x[3] and x[1] == m.get("el_before") and m.get("ct_before") is not None and x[2] > m["ct_before"] + 0.001), None) if name == "resume" else None
            m["to_first_ct_change_ms"] = first[0] - ms if first else None
            if adv:
                t_adv = adv[0]
                el = adv[1]
                ramp = [(r[0] - t_adv, r[4]) for r in S if r[1] == el and t_adv - 50 <= r[0] <= t_adv + 600]
                m["vol_at_audio"] = adv[4]
                full = next((r for r in S if r[1] == el and r[0] >= t_adv and r[4] >= 0.98 * ref), None)
                m["to_full_after_audio_ms"] = (full[0] - t_adv) if full else None
                m["to_full_ms"] = (full[0] - ms) if full else None
                after = [r for r in S if r[1] == el and t_adv + 1000 <= r[0] <= min(horizon, t_adv + 8000)]
                m["vol_min_1s_to_8s"] = min((r[4] for r in after), default=None)
                m["vol_end"] = after[-1][4] if after else None
                m["ramp"] = [(t, v) for t, v in ramp[::max(1, len(ramp) // 8)]]
            if adv:
                early = [r for r in S if r[1] == adv[1] and ms <= r[0] < adv[0] - 150 and r[4] >= 0.98 * ref]
                if adv[4] >= 0.9 * ref and early:
                    m["WARN"] = "the rise finished before the audio began (ramp spent on silence): sound starts at full volume"
            if name == "resume" and (not adv or m.get("to_full_after_audio_ms") is None):
                m["FAIL"] = "no audio" if not adv else "volume never reached the full level"
            elif name == "resume" and m.get("vol_min_1s_to_8s") is not None and m["vol_min_1s_to_8s"] < 0.9 * ref:
                m["FAIL"] = f"volume dipped to {m['vol_min_1s_to_8s']} after the rise"
        elif name in ("next", "swipenext", "previous"):
            tr = [t for t in traces if ms <= t["ms"] <= horizon]
            base = next((t for t in reversed(traces) if t["ms"] <= ms), None)
            if base:
                e1 = next((t for t in tr if t["eng_uri"] != base["eng_uri"]), None)
                u1 = next((t for t in tr if t["ui_uri"] != base["ui_uri"]), None)
                m["to_eng_uri_change_ms"] = e1["ms"] - ms if e1 else None
                m["to_ui_uri_change_ms"] = u1["ms"] - ms if u1 else None
                if name == "previous" and not e1:
                    r1 = next((t for t in tr if t["eng_pos"] is not None and base["eng_pos"] is not None and t["eng_pos"] < base["eng_pos"] - 2000), None)
                    m["to_eng_restart_ms"] = r1["ms"] - ms if r1 else None
            w = [s for s in S if ms <= s[0] <= horizon]
            lastct = {}
            tfirst = None
            for s in w:
                prev = lastct.get(s[1])
                lastct[s[1]] = s[2]
                if not s[3] and prev is not None and s[2] > prev + 0.03 and s[0] > ms + 100:
                    ldone = [e for e in events if ms <= e[0] <= s[0] and e[1] in ("loadstart", "emptied", "seeking")]
                    if ldone or s[1] != m.get("el_before"):
                        tfirst = s
                        break
            m["to_audio_ms"] = tfirst[0] - ms if tfirst else None
            if tfirst:
                m["vol_at_audio"] = tfirst[4]
        elif name == "seek":
            tr = [t for t in traces if ms <= t["ms"] <= horizon]
            m["eng_pos_after"] = [t["eng_pos"] for t in tr[::10]][:6]
        out.append(m)
    return out


def natural_boundaries(traces, merged, samples, cmds):
    """Song changes the engine made by itself (end of a song): when the old audio ended, when the new began, at what volume."""
    out = []
    S = [s for s in samples if not s[7]]
    ok = lambda u: u not in ("", "null", "None")
    for i in range(1, len(traces)):
        a, b = traces[i - 1], traces[i]
        if a["eng_uri"] == b["eng_uri"] or not ok(a["eng_uri"]) or not ok(b["eng_uri"]):
            continue
        if near(cmds, b["ms"], ("next", "previous", "swipenext", "play"), before=1500, after=1500):
            continue
        cand = [j for j, sp in enumerate(merged) if sp[1] >= a["ms"] - 2500 and sp[0] <= b["ms"] + 1500]
        k = cand[0] if cand else None
        d = dict(eng_switch_ms=b["ms"], from_title=a["eng_title"], to_title=b["eng_title"], old_eng_pos=a["eng_pos"], old_dur=a["ui_dur"], line=b["no"])
        if k is not None:
            d["old_audio_end_ms"] = merged[k][1]
            d["switch_after_audio_end_ms"] = b["ms"] - merged[k][1]
            if merged[k][1] > b["ms"] + 1500:      # one unbroken span across the switch: no silence at all
                st, el = b["ms"], merged[k][2]
                d["audio_gap_ms"] = 0
            elif k + 1 < len(merged) and merged[k + 1][0] - merged[k][1] < 12000:
                st, el = merged[k + 1][0], merged[k + 1][2]
                d["audio_gap_ms"] = st - merged[k][1]
            else:
                st = None
            if st is not None:
                d["new_audio_start_ms"] = st
                v = [r[4] for r in S if r[1] == el and st + 1500 <= r[0] <= st + 4000]
                d["new_vol_min_1.5s_to_4s"] = min(v) if v else None
        out.append(d)
    return out


# ── fades: the resume rise, the pause ramp, the crossfade ─────────────────────

def _fit_cubic(rows, t_lo, t_hi, own, model):
    """Best start time t0 (ms) of [model](t - t0) -> 0..1, vol = own * k^3, over the samples in [t_lo, t_hi]. -> (t0, rms)"""
    pts = [(r[0], r[4]) for r in rows if t_lo <= r[0] <= t_hi]
    if len(pts) < 4:
        return None, None
    best = None
    for t0 in range(int(t_lo) - 300, int(t_hi) + 1, 4):
        e = sum((v - max(0.001, own * model(t - t0) ** 3)) ** 2 for t, v in pts)
        if best is None or e < best[0]:
            best = (e, t0)
    return best[1], round((best[0] / len(pts)) ** 0.5, 4)


def fade_metrics(samples, events, cmds, rise_ms=500, pause_ms=160):
    """Per resume: when the element is silent, when its clock moves, when the volume starts to rise (cubic fit),
    how long to the full level, the level reached. Per pause: the ramp down (cubic fit) and the pause event."""
    S = [s for s in samples if not s[7]]
    rises, pauses = [], []
    for idx, (ms, name, no) in enumerate(cmds):
        nxt = cmds[idx + 1][0] if idx + 1 < len(cmds) else ms + 20000
        before = [s for s in S if s[0] <= ms]
        if not before:
            continue
        last = before[-1]
        el, ct0 = last[1], last[2]
        E = [s for s in S if s[1] == el]
        if name == "pause" and not last[3]:
            pe = next((e for e in events if e[2] == el and ms <= e[0] <= ms + 3000 and e[1] == "pause"), None)
            lvl = max(s[4] for s in E if ms - 600 <= s[0] <= ms) or last[4]
            ramp = [(s[0] - ms, s[4]) for s in E if ms - 60 <= s[0] <= (pe[0] if pe else ms + 400)]
            d = dict(cmd="pause", ms=ms, line=no, level_before=lvl, to_pause_event_ms=(pe[0] - ms) if pe else None,
                     vol_at_pause_event=pe[4] if pe else None, ramp=ramp[::max(1, len(ramp) // 10)])
            t0, rms = _fit_cubic(E, ms - 50, (pe[0] if pe else ms + 300), lvl, lambda dt: max(0.0, 1 - dt / pause_ms))
            d["fit_start_after_cmd_ms"] = None if t0 is None else t0 - ms
            d["fit_rms"] = rms
            vols = [v for _, v in ramp]
            d["monotonic_down"] = all(b <= a + 0.02 for a, b in zip(vols, vols[1:]))
            pauses.append(d)
        elif name == "resume":
            pre = [s for s in before if s[1] == el and s[3]]
            own = max([s[4] for s in E if ms - 12000 <= s[0] <= ms] or [1.0])  # the level the element is brought back to while paused
            horizon = min(nxt, ms + 12000)
            win = [s for s in E if ms <= s[0] <= horizon]
            tm = next((s for i, s in enumerate(win) if not s[3] and s[2] > ct0 + 0.03), None)
            d = dict(cmd="resume", ms=ms, line=no, el=el, own=own, ct_before=ct0, t_move_ms=(tm[0] - ms) if tm else None)
            if tm:
                silent = [s for s in win if s[4] <= 0.0015]
                d["t_silent_ms"] = (silent[0][0] - ms) if silent else None
                # media.rise sets the silence ~20-100 ms after the command; until then the element still has the level it
                # was restored to while paused (not heard: paused). From the first sample below half of it, up to the clock
                # moving, it must stay silent.
                setp = next((s for s in win if s[4] < 0.5 * own), None)
                d["t_rise_set_ms"] = (setp[0] - ms) if setp else None
                gate = [s for s in win if setp and setp[0] <= s[0] < tm[0] - 60]
                d["vol_max_before_move"] = max((s[4] for s in gate), default=None)
                d["vol_at_move"] = tm[4]
                # exact (event-stamped) view: volumechange events carry the element's own currentTime at the moment
                vc = [e for e in events if e[2] == el and e[1] == "volumechange" and ms <= e[0] <= horizon]
                sil = next((e for e in vc if e[4] <= 0.0015), None)
                up = next((e for e in vc if sil and e[0] > sil[0] and e[4] > 0.002), None)
                d["ev_silence_set_ms"] = (sil[0] - ms) if sil else None
                d["ev_silence_set_clock_ahead_s"] = round(sil[3] - ct0, 3) if sil else None
                d["ev_rise_first_ms"] = (up[0] - ms) if up else None
                d["ev_rise_first_clock_ahead_s"] = round(up[3] - ct0, 3) if up else None
                t0, rms = _fit_cubic(E, setp[0] if setp else tm[0] - 100, tm[0] + rise_ms + 200, own, lambda dt: min(1.0, max(0.0, dt / rise_ms)))
                d["fit_start_after_move_ms"] = None if t0 is None else t0 - tm[0]
                d["fit_rms"] = rms
                after = [s for s in E if tm[0] + 1000 <= s[0] <= min(horizon - 100, tm[0] + 6000) and not s[3]]
                lvl_end = sorted(s[4] for s in after)[len(after) // 2] if after else None
                d["level_after_median"] = lvl_end
                d["level_ratio_to_own"] = round(lvl_end / own, 3) if lvl_end and own else None
                full = next((s for s in E if s[0] >= tm[0] and s[4] >= 0.98 * own), None)
                d["to_full_after_move_ms"] = (full[0] - tm[0]) if full else None
                low = [s[4] for s in E if (full[0] if full else tm[0]) <= s[0] <= min(horizon - 100, tm[0] + 6000) and not s[3]]
                d["vol_min_after_full"] = min(low) if low else None
                d["ramp"] = [(s[0] - tm[0], s[4]) for s in E if tm[0] - 100 <= s[0] <= tm[0] + 700][::2]
            rises.append(d)
    return rises, pauses


def crossfade_metrics(samples, bounds, traces, other, own=1.0):
    """Per natural song change: the element volume 12 s before to 6 s after the switch (every 500 ms), the longest span at
    volume < 0.1 / < 0.02 while the clock advances, silence (no clock progress) around the switch, the level 3-6 s after."""
    S = [s for s in samples if not s[7]]
    out = []
    xlines = [(o[0], o[3], o[2][:140]) for o in other if "crossfade" in o[2].lower()]
    for b in bounds:
        sw = b["eng_switch_ms"]
        rows = [s for s in S if sw - 12000 <= s[0] <= sw + 6000]
        if not rows:
            continue
        tl = []
        for t in range(-12000, 6001, 500):
            near = min(rows, key=lambda s: abs(s[0] - (sw + t)))
            tl.append((t, near[4], near[1], 0 if near[3] else 1))
        def longest(th):
            best = cur = 0
            start = None
            for a, c in zip(rows, rows[1:]):
                low = c[4] < th and not c[3]
                if low:
                    start = start if start is not None else a[0]
                    cur = c[0] - start
                    best = max(best, cur)
                else:
                    start = None
            return best
        aft = [s[4] for s in rows if sw + 3000 <= s[0] <= sw + 6000]
        pre = [s for s in rows if s[0] < sw - 6500]
        out.append(dict(switch_ms=sw, line=b["line"], from_title=b["from_title"], to_title=b["to_title"], audio_gap_ms=b.get("audio_gap_ms"),
                        vol_before_fade=(pre[-1][4] if pre else None), longest_below_0_1_ms=longest(0.1), longest_below_0_02_ms=longest(0.02),
                        vol_min_overall=min(s[4] for s in rows), level_3_6s_after_min=min(aft) if aft else None,
                        timeline=tl, log=[x for x in xlines if sw - 14000 <= x[0] <= sw + 6000]))
    return out


def log_flags(other):
    pats = [("player stalled", r"player stalled"), ("reload", r"(?<![a-z])reload"), ("crossfade", r"crossfade"), ("volume", r"volume"),
            ("ad", r"\bad (muted|stuck)|ad .*asked the page|blocked ad request|\"kind\":\"ad\""), ("http 429", r"http 429"), ("call timeout", r"timeout after \d+ ms"),
            ("not taken", r"not taken|command failed|transport failed|page not answering|page silent"), ("crash", r"FATAL|AndroidRuntime")]
    out = {}
    for ms, tag, text, no in other:
        for k, rx in pats:
            if re.search(rx, text, re.I):
                out.setdefault(k, []).append((ms, no, text[:200]))
    return out


def analyse(base, ref=1.0):
    cmds, traces, other = L.parse_log(base + ".log")
    samples, events = L.load_media(base + ".media.jsonl")
    ui = check_ui(traces, cmds)
    med, gaps, merged = check_media(samples, events, cmds, ref)
    bounds = natural_boundaries(traces, merged, samples, cmds)
    flags = log_flags(other)
    cm = command_metrics(samples, events, cmds, traces, ref)
    rises, pauses = fade_metrics(samples, events, cmds)
    xf = crossfade_metrics(samples, bounds, traces, other)
    # A silence of < 3 s at a song's own end is judged as a boundary (tools/pilot.py: limit 3 s), not as a stall.
    med = [e for e in med if not (e["kind"] == "silence_gap" and any(abs(e["ms"] - b.get("old_audio_end_ms", -10**12)) < 500 and (b.get("audio_gap_ms") or 0) < 3000 for b in bounds))]
    allev = sorted(ui + med, key=lambda e: e["ms"])
    counts = Counter((e["kind"], e["scope"]) for e in allev)
    return dict(base=base, trace_lines=len(traces), media_samples=len(samples), media_events=len(events), commands=[(c[0], c[1]) for c in cmds],
                counts={f"{k}/{s}": n for (k, s), n in sorted(counts.items())}, events=allev, gaps=gaps, boundaries=bounds, cmd_metrics=cm,
                rises=rises, pause_fades=pauses, crossfades=xf, log_flags={k: len(v) for k, v in flags.items()}, log_flag_lines={k: v[:6] for k, v in flags.items()})


def summary(a, limit=12):
    L_ = [f"{os.path.basename(a['base'])}: {a['trace_lines']} trace lines, {a['media_samples']} media samples, {len(a['commands'])} commands"]
    spont = {k: n for k, n in a["counts"].items() if k.endswith("/spont") and not k.startswith("cover_same_after")}
    L_.append("anomalies: " + (", ".join(f"{k}={n}" for k, n in a["counts"].items()) or "none"))
    L_.append("log flags: " + (", ".join(f"{k}={n}" for k, n in a["log_flags"].items()) or "none"))
    shown = 0
    for e in a["events"]:
        if e["scope"] == "spont" and not e.get("soft") and shown < limit:
            L_.append(f"  [{e['kind']}] @{e['ms']} log:{e['line']}{'-' + str(e['line2']) if e['line2'] else ''}  {e['detail']}")
            shown += 1
    return "\n".join(L_), bool(spont)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--ref", type=float, default=1.0, help="reference (full) element volume")
    a = ap.parse_args()
    res = analyse(a.base, a.ref)
    txt, bad = summary(res)
    print(txt)
    if a.json:
        json.dump(res, open(a.base + ".analysis.json", "w"), indent=1, default=str)
    sys.exit(1 if bad else 0)
