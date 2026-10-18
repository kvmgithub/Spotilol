#!/bin/bash
# System-wide media routing capture: logcat (routing tags) + dumpsys media_router every 2 s.
# usage: SERIAL=... tools/routecap.sh <name>; stop with: touch tools/captures/<name>.stop
ADB=${ADB:-$HOME/android-sdk/platform-tools/adb}
SERIAL=${SERIAL:?set SERIAL to the adb serial, e.g. host:port}
D=$(dirname "$0")/captures/$1; mkdir -p "$D"; rm -f "$D.stop"
"$ADB" -s "$SERIAL" logcat -c
"$ADB" -s "$SERIAL" logcat -v time | grep --line-buffered -E "MR2|Route2|RemoteDevice|MediaDevice|InfoMediaManager|LocalMediaManager|MediaOutput|spotify|Spotify|myspoti|MediaRouter|RouteListing|oneconnect.*Media" > "$D/routing.log" &
LP=$!
i=0
while [ ! -f "$D.stop" ]; do
  "$ADB" -s "$SERIAL" shell dumpsys media_router > "$D/mr_$(printf %03d $i).txt" 2>/dev/null
  i=$((i+1)); sleep 2
done
kill $LP
