#!/usr/bin/env bash
# Records what happens in app.myspoti while the user drives the phone.
# usage: tools/capture.sh <name>   (stop with: touch tools/captures/<name>.stop)
set -u
ADB=${ADB:-$HOME/android-sdk/platform-tools/adb}
SERIAL=${SERIAL:-$("$ADB" devices | awk 'NR==2 && $2=="device"{print $1}')}  # first device unless SERIAL is set
NAME=${1:-session}
OUT="$(dirname "$0")/captures/$NAME"
mkdir -p "$OUT"; rm -f "$OUT.stop"
a() { "$ADB" -s "$SERIAL" "$@"; }
UIDN=$(a shell pm list packages -U app.myspoti | grep -oE 'uid:[0-9]+' | cut -d: -f2)
a logcat -c
a shell dumpsys gfxinfo app.myspoti reset >/dev/null
a logcat -v time --uid="$UIDN" > "$OUT/logcat.txt" &
LP=$!
# Sample the system media session + audio state every second.
while [ ! -e "$OUT.stop" ]; do
  ts=$(date +%H:%M:%S.%N | cut -c1-12)
  st=$(a shell dumpsys media_session | sed -n '/package=app.myspoti$/,/queueTitle/p' | grep -oE 'state=[A-Z]+|description=[^,]*' | tr '\n' ' ')
  au=$(a shell dumpsys audio | grep -E "u/pid:$UIDN/.*state:" | grep -oE 'state:[a-z]+' | head -1)
  echo "$ts $st $au" >> "$OUT/session.txt"
  sleep 1
done
kill $LP 2>/dev/null
a shell dumpsys gfxinfo app.myspoti > "$OUT/gfxinfo.txt"
a shell dumpsys activity activities | grep -E 'topResumedActivity' > "$OUT/activity.txt"
echo "capture saved in $OUT"
