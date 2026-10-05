#!/system/bin/sh
# Run by Wake (root) on a DSP detection. Hands what you say next to your desktop assistant through the uid-1000
# listener (Listen.java: Android's on-device recogniser, then the desktop's reply played on the phone). Only a
# miss (the desktop had nothing, couldn't be reached, or the listener failed) opens the stock assistant instead:
# wake the screen, clear the swipe keyguard (with a PIN the bouncer shows), press the assist key.
# Nobody spoke after the wake word → nothing happens except a short "gave up" tone.
# $D/NOGEMINI = test mode: everything runs, but a miss only logs "WOULD GEMINI".
# Holds a 30 s kernel wakelock: listen ≤9 s + desktop + reply playback, so the phone can't suspend half-way.
#
# Settings come from $D/stwake.conf (same file the supervisor reads):
#   HOME_BSSID_RE='aa:bb:cc:dd:ee:(01|02)'  your home access point's BSSIDs (both radios), as an ERE
#   DOCK_ANOM=70                             magnetometer anomaly (µT) above which the phone counts as docked
#   HOME_RSSI_MAX=72                         only gate when the home AP is stronger than -72 dBm
echo "stwake-fire 30000000000" > /sys/power/wake_lock
D=/data/local/tmp/stwake; PV=/data/local/tmp/pv; R=$PV/run
HOME_BSSID_RE=; DOCK_ANOM=70; HOME_RSSI_MAX=72
[ -f $D/stwake.conf ] && . $D/stwake.conf
ts() { date '+%m-%d %H:%M:%S'; }
done_() { echo stwake-fire > /sys/power/wake_unlock; exit 0; }
gemini() {
  if [ -e $D/NOGEMINI ]; then echo "$(ts) WOULD GEMINI ($1)"; return; fi
  echo "$(ts) gemini ($1)"
  input keyevent KEYCODE_WAKEUP
  wm dismiss-keyguard
  for i in 1 2 3 4 5 6 7 8 9 10; do dumpsys window 2>/dev/null | grep -q isKeyguardShowing=true || break; sleep 0.1; done
  input keyevent KEYCODE_VOICE_ASSIST
}
# DESK GATE (optional): when the phone sits on a magnetic (MagSafe-style) charger at the desk, the desktop's own
# microphone owns the wake word, so the phone stays quiet. Docked = home AP close by AND a magnet on the back:
# field minus the phone's own magnetism measured ~90 µT docked vs ~50 µT loose (Earth's field). Car mount is
# off home Wi-Fi → fires. A home AP with two radios needs BOTH BSSIDs in HOME_BSSID_RE: the phone hops between
# them, and matching only one let docked wakes through.
# Fails open: any missing reading → fire. Runs BEFORE the listener only on the home AP (magread takes 0.4–0.6 s),
# so a docked wake never beeps; away from home the listener is poked at once.
W=$(cmd wifi status 2>/dev/null)
athome=
[ -n "$HOME_BSSID_RE" ] && echo "$W" | grep -qE "BSSID: ($HOME_BSSID_RE)" && athome=1
if [ -n "$athome" ] && \
   [ "$(echo "$W" | grep -m1 -oE 'RSSI: -?[0-9]+' | grep -oE '[0-9]+$')" -lt "$HOME_RSSI_MAX" ] 2>/dev/null; then
  M=$($D/magread 1 2>/dev/null)
  A=$(echo "$M" | grep -oE '^anom=[0-9]+' | cut -d= -f2)
  if [ -n "$A" ] && [ "$A" -gt "$DOCK_ANOM" ]; then
    echo "$(ts) skip: docked at desk ($M)"; done_
  fi
fi
echo "$(ts) fire ($(echo "$W" | grep -m1 -oE 'RSSI: -?[0-9]+') $M)"
# the listener: lazy-start it if it isn't running (the first wake after a reboot pays ~0.3 s), then poke
if ! kill -0 "$(cat $R/listen.pid 2>/dev/null)" 2>/dev/null; then
  mkdir -p $R; chown 1000:1000 $R
  nohup setsid su 1000 -c "CLASSPATH=$D/listen.jar exec app_process /system/bin Listen" >> $PV/listen.log 2>&1 < /dev/null &
  for i in $(seq 1 30); do [ -p $R/poke ] && grep -q "listener up pid=$(cat $R/listen.pid 2>/dev/null)" $PV/listen.log 2>/dev/null && break; sleep 0.1; done
  echo "$(ts) listener started"
fi
id=w$(date +%s)$$
# away = not on the home Wi-Fi → the desktop can refuse things that only make sense at home (e.g. "play …" on the TV)
[ -n "$athome" ] && away= || away=" away"
if ! timeout 1 sh -c "echo $id$away > $R/poke" 2>/dev/null; then gemini "listener not reading"; done_; fi
# wait for its verdict: ≤9 s listening + 1.5 s cap grace + desktop (2.5 s connect / 8 s read)
for i in $(seq 1 200); do [ -e $R/$id.json ] && break; sleep 0.1; done
if [ ! -e $R/$id.json ]; then gemini "no verdict"; done_; fi
J=$(cat $R/$id.json); rm -f $R/$id.json
heard=$(echo "$J" | grep -oE '"heard": ?"[^"]*"' | head -1 | sed -E 's/"heard": ?"//; s/"$//')
why=$(echo "$J" | grep -oE '"why": ?"[^"]*"' | sed -E 's/"why": ?"//; s/"$//')
if echo "$J" | grep -qE '"miss": ?false'; then
  echo "$(ts) hit: $(echo "$J" | grep -oE '"what": ?"[^"]*"') heard \"$heard\""
elif [ "$why" = "nothing heard" ]; then            # the desktop stripped it to nothing: only the wake word again
  echo "$(ts) quiet: only the wake word (\"$heard\")"
elif [ -n "$heard" ] || echo "$why" | grep -q 'recogniser error'; then
  gemini "miss: \"$heard\" $why"
else
  echo "$(ts) quiet: $why"
fi
done_
