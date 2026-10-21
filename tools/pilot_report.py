#!/usr/bin/env python3
"""Rebuilds the analysis and REPORT.md of a pilot run from its raw files.

usage: tools/pilot_report.py DIR      (DIR = tools/qa-results/pilot-<date>; needs results.json from tools/pilot.py)
Every number comes from DIR/<scenario>.log (logcat -v epoch) and DIR/<scenario>.media.jsonl (page sampler).
"""
import json, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pilot_lib as L  # noqa: E402
import uitrace_check as U  # noqa: E402
import pilot  # noqa: E402

KEYWORDS = re.compile(r"ad muted|ad stuck|ads?\.(off|skip)|ad .*asked|stalled|reload|silent|timeout|not taken|failed|jserror|ERROR|crossfade|"
                      r"media\.(mute|fade|rise)|player\.(play|next|previous)|http 4|http 5|test command|ready=|page (start|finished)|ended", re.I)


def ad_near(other, ms, w=12000):
    return [o for o in other if abs(o[0] - ms) <= w and re.search(r"\bad (muted|stuck)|ad .*asked the page", o[2])]


def telling(base, f, n=6):
    """3-6 log lines (with line numbers) around a failure."""
    path = base + ".log"
    cmds, traces, other = L.parse_log(path)
    ms = f.get("ms")
    out = []
    if ms:
        near = [o for o in other if ms - 2500 <= o[0] <= ms + 7000 and KEYWORDS.search(o[2]) and not o[2].startswith("console WARNING")]
        cm = [(c[0], "MspPlayer", "test command " + c[1], c[2]) for c in cmds if ms - 6000 <= c[0] <= ms + 3000]
        near = sorted(set(near + cm))
        # keep the most telling ones, spread out
        pick = near if len(near) <= n else near[:2] + near[len(near) // 2 - 1:len(near) // 2 + 1] + near[-2:]
        out = [f"{os.path.basename(path)}:{o[3]}  {o[2][:170]}" for o in pick[:n]]
    if f.get("line") and f.get("kind", "").startswith(("ui_", "eng_", "trace", "cover")):
        with open(path, errors="replace") as fh:
            for i, line in enumerate(fh, 1):
                if i == f["line"]:
                    out.insert(0, f"{os.path.basename(path)}:{i}  " + re.sub(r"^\s*\d+\.\d+\s+\d+\s+\d+\s+\w\s+", "", line.strip())[:200])
                    break
    return out[:n + 1]


def scenario_failures(name, base, an, res):
    f = pilot.failures(an)
    if name == "H":
        return list(res.get("fail", []))  # no media sampler in H: the scenario's own verdict
    if name == "X":
        f = pilot.xfade_failures(an, (res.get("key", {}).get("notes") or {}).get("own") or 1)
    if name in ("A1", "A2", "A2b", "D"):
        f += pilot.fade_failures(an, os.path.basename(base))
    if name in ("B", "B2", "B3", "C", "A2", "A2b"):
        f += pilot.boundary_failures(an)
    if name in ("A2", "A2b"):
        for lab, c in (res.get("key", {}).get("timers") or {}).items():
            if "error" in c:
                f.append(dict(kind="timer_check", detail=f"{lab}: {c['error']}", file="results.json"))
            else:
                if c.get("vis") != "visible":
                    f.append(dict(kind="visibility", detail=f"{lab}: visibilityState={c['vis']} hidden={c['hidden']} after {c['after_s']} s", file="results.json"))
                if max(c["chain8x38_ms"]) > 800:
                    f.append(dict(kind="timer_throttled", detail=f"{lab}: 8 x setTimeout(38) took {c['chain8x38_ms']} ms after {c['after_s']} s", file="results.json"))
        if not an["boundaries"]:
            f.append(dict(kind="no_song_change_after_seek", detail="engine never changed song after the seek to duration-8 s", file=os.path.basename(base) + ".log"))
    if name in ("B", "B2", "B3", "C") and not an["boundaries"]:
        f.append(dict(kind="no_song_change", detail="no song boundary crossed", file=os.path.basename(base) + ".log"))
    fin = res.get("key", {}).get("final")
    if name in ("A1", "D") and isinstance(fin, dict) and not (fin["volume"] >= 0.98 * fin["own"] and not fin["paused"]):
        f.append(dict(kind="final_volume", detail=f"final media.state {fin}", file="results.json"))
    return f


SCEN = [("A1", "A1_short_pause", "A1 short pause (5 s) x3, resume rise + latency"),
        ("A2", "A2_long_pause", "A2 long pause (>= 6 min, screen off), page visibility/timers, resume rise, end of song after it"),
        ("A2b", "A2b_long_pause", "A2b long pause again (different song)"),
        ("B", "B_end_of_track", "B end of track (seek to duration - 8 s)"),
        ("B2", "B2_end_of_track", "B2 end of track, second song"),
        ("B3", "B3_end_of_track", "B3 end of track, third song"),
        ("C", "C_long_play", "C 4 min continuous play across a boundary"),
        ("D", "D_commands", "D pause/resume x10, next/previous, swipenext bursts, seeks"),
        ("X", "X_crossfade", "X crossfade 6 s over 2 natural song ends, then crossfade 0 and a third end"),
        ("H", "H_headless", "H headless: pause 3 min, process / foreground service, then a resume broadcast")]


def fmt(v):
    return json.dumps(v, default=str)


def main(d):
    results = json.load(open(os.path.join(d, "results.json")))
    md = ["# Pilot report", "", f"Run directory: `{d}`  ", "Times are the phone's epoch ms; `file:N` = line N of that file in this directory. "
          "Latencies start at the app's `test command` log line (receiver side, adb broadcast overhead not included).", ""]
    summary, ranked = [], []
    for key, stem, title in SCEN:
        base = os.path.join(d, stem)
        if not os.path.exists(base + ".log"):
            continue
        res = results.get(key, {})
        an = U.analyse(base, 1.0)
        json.dump(an, open(base + ".analysis.json", "w"), indent=1, default=str)
        open(base + ".analysis.txt", "w").write(U.summary(an, 40)[0] + "\n")
        fails = scenario_failures(key, base, an, res)
        _, _, other = L.parse_log(base + ".log")
        for f in fails:
            f["ad"] = bool(f.get("ms") and ad_near(other, f["ms"]))
        verdict = "PASS" if not fails else "FAIL"
        summary.append((key, verdict, title, len(fails)))
        md += [f"## {title}: **{verdict}**", ""]
        md += [f"Files: `{stem}.log`, `{stem}.media.jsonl`, `{stem}.analysis.txt|json`. Trace lines {an['trace_lines']}, media samples {an['media_samples']}, commands {len(an['commands'])}.", ""]
        cm = an["cmd_metrics"]
        g = lambda n, k: [m.get(k) for m in cm if m["cmd"] == n]
        k = res.get("key", {})
        if key in ("A1", "A2", "A2b", "D"):
            md.append(f"- resume: ms to first audio {g('resume', 'to_audio_ms')}, to 'playing' event {g('resume', 'to_playing_event_ms')}, "
                      f"first currentTime change {g('resume', 'to_first_ct_change_ms')}, to full volume after audio start {g('resume', 'to_full_after_audio_ms')}, volume when audio starts {g('resume', 'vol_at_audio')}, "
                      f"min volume 1-8 s after {g('resume', 'vol_min_1s_to_8s')}")
            warn = [m["WARN"] for m in cm if m.get("WARN")]
            if warn:
                md.append(f"- WARN x{len(warn)}: {warn[0]}")
            md.append(f"- pause: ms to the element's pause event {g('pause', 'to_pause_event_ms')}, volume then {g('pause', 'vol_at_pause_event')}")
        if key in ("A2", "A2b"):
            md.append(f"- page checks while paused (screen off): {fmt(k.get('timers'))}")
            md.append(f"- state: {fmt({x: k['notes'].get(x) for x in ('wakefulness', 'resumed_activity')})}; after resume media.state {fmt(k['notes'].get('after_resume'))}")
        if key in ("A1", "D"):
            md.append(f"- final media.state: {fmt(k.get('final'))}")
        if key == "D":
            md.append(f"- random pause gaps (s): {k.get('pause_gaps_s')}")
            for n_ in ("next", "previous", "swipenext"):
                md.append(f"- {n_}: ms to engine song change {g(n_, 'to_eng_uri_change_ms')}, to app song change {g(n_, 'to_ui_uri_change_ms')}, to first audio {g(n_, 'to_audio_ms')}"
                          + (f", to restart {g(n_, 'to_eng_restart_ms')}" if n_ == "previous" else ""))
            md.append(f"- seek: engine positions after each seek (sampled) {g('seek', 'eng_pos_after')}")
        if key in ("A1", "A2", "A2b", "D"):
            for i_, x in enumerate(an.get("rises", [])):
                md.append(f"- rise {i_ + 1} (log:{x['line']}): rise-set +{x.get('t_rise_set_ms')} ms, clock moved +{x['t_move_ms']} ms, vol max before move {x.get('vol_max_before_move')}, vol at move {x.get('vol_at_move')}, "
                          f"cubic start {x.get('fit_start_after_move_ms')} ms from the move (fit rms {x.get('fit_rms')}), events: silence set +{x.get('ev_silence_set_ms')} ms with the clock {x.get('ev_silence_set_clock_ahead_s')} s ahead of the pause point, first rise step +{x.get('ev_rise_first_ms')} ms with the clock {x.get('ev_rise_first_clock_ahead_s')} s ahead, full {x.get('to_full_after_move_ms')} ms after the move, "
                          f"level {x.get('level_after_median')} / pre-pause {x['own']}, min after full {x.get('vol_min_after_full')}")
            for i_, x in enumerate(an.get("pause_fades", [])):
                md.append(f"- pause {i_ + 1} (log:{x['line']}): element pause event +{x['to_pause_event_ms']} ms, volume then {x['vol_at_pause_event']}, ramp (ms, vol) {x['ramp']}, cubic start {x.get('fit_start_after_cmd_ms')} ms (rms {x.get('fit_rms')})")
        if key == "X":
            for i_, x in enumerate(an.get("crossfades", [])):
                md.append(f"- end {i_ + 1} ({'crossfade 6 s' if i_ < 2 else 'crossfade 0'}) `{x['from_title']}` -> `{x['to_title']}` (log:{x['line']}): silence at the switch {x['audio_gap_ms']} ms, "
                          f"longest < 0.1: {x['longest_below_0_1_ms']} ms, < 0.02: {x['longest_below_0_02_ms']} ms, min {x['vol_min_overall']}, 3-6 s after min {x['level_3_6s_after_min']}; log {[l[2] for l in x['log']]}")
                md.append("  - element volume vs time from the engine's song switch (ms:vol): " + " ".join(f"{t}:{v}" for t, v, _, _ in x["timeline"] if -6500 <= t <= 2500))
        if key == "H":
            md.append(f"- {fmt({x: v for x, v in k.items() if x != 'timeline'})}")
            md.append(f"- process / foreground flag while paused: {fmt(k.get('timeline'))}")
        for b in an["boundaries"]:
            md.append(f"- natural song change `{b['from_title']}` -> `{b['to_title']}`: audio gap {b.get('audio_gap_ms')} ms, engine switch {b.get('switch_after_audio_end_ms')} ms after old audio end, "
                      f"new song min volume 1.5-4 s after start {b.get('new_vol_min_1.5s_to_4s')} (log:{b['line']})")
        md.append(f"- anomaly counts: {an['counts'] or 'none'}; log flags: {an['log_flags'] or 'none'}")
        for f in fails:
            ranked.append((key, f))
            md += ["", f"### FAIL {f['kind']}{' (an ad was in progress)' if f.get('ad') else ''}", f"- {f['detail']}",
                   f"- where: `{f.get('file')}`" + (f" line {f['line']}" + (f"-{f['line2']}" if f.get('line2') else "") if f.get("line") else "")]
            for t in telling(base, f):
                md.append(f"  - `{t}`")
        md.append("")
    if "F" in results:
        r = results["F"]
        md += [f"## F qa.py --battery all: **{'PASS' if not r['fail'] else 'FAIL'}**", "", f"- {fmt(r.get('key'))}"]
        for f in r["fail"]:
            ranked.append(("F", f))
            md.append(f"- FAIL {f['detail']} (`{f.get('file')}`)")
        md.append("")
    notes = os.path.join(d, "NOTES.md")
    if os.path.exists(notes):
        md.insert(4, open(notes).read())
    md.insert(4, "| scenario | verdict | failures |\n|---|---|---|\n" + "\n".join(f"| {t} | {v} | {n} |" for _, v, t, n in summary) + "\n")
    open(os.path.join(d, "REPORT.md"), "w").write("\n".join(md) + "\n")
    print("\n".join(f"{k} {v} {t} ({n})" for k, v, t, n in summary))
    for k, f in ranked:
        print(f" {k} {f['kind']}{' [ad]' if f.get('ad') else ''}: {f['detail'][:150]} @ {f.get('file')}:{f.get('line')}")


if __name__ == "__main__":
    main(sys.argv[1])
