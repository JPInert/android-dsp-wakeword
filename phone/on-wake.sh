#!/system/bin/sh
# Run by Wake (root) on a DSP detection. Hands what you say next to your desktop assistant through the uid-1000
# listener (Listen.java: Android's on-device recogniser, then the desktop's reply played on the phone).
# In my setup the desktop answers anything it has no fixed command for by starting a background job ("On it.", then
# the answer a little later, see Listen.pending). The stock assistant only opens when the desktop can't be reached,
# the listener isn't reading, or no verdict comes back (or when your desktop answers "miss", like desk_stub.py does):
# wake the screen, clear the swipe keyguard (with a PIN the bouncer shows), press the assist key.
# Nobody spoke after the wake word → nothing happens except a short "gave up" tone.
# $D/NOGEMINI = test mode: everything runs, but a miss only logs "WOULD GEMINI" (it never opens the assistant).
# Holds a 60 s kernel wakelock: listen ≤9 s + desktop (some commands wait on another device, read timeout 30 s) +
# reply playback, so the phone can't suspend half-way.
#
# Settings come from $D/stwake.conf (gate.sh reads it): the dock-gate bars, plus for the VPN (optional, see wgaway.sh)
#   WG_TUNNEL=home            the tunnel's name in the WireGuard app. Empty = never raise a tunnel.
#   WG_PHONE_IP=...           the phone's Address from the tunnel config, without the /32 (how we tell it is up)
echo "stwake-fire 60000000000" > /sys/power/wake_lock
D=/data/local/tmp/stwake; PV=/data/local/tmp/pv; R=$PV/run
WG_TUNNEL=; WG_PHONE_IP=
. $D/gate.sh                 # the dock-gate bars, ts, start_l/poke1/poke; shared with alertd.sh. Reads stwake.conf.
done_() { echo stwake-fire > /sys/power/wake_unlock; exit 0; }
gemini() {
  if [ -e $D/NOGEMINI ]; then echo "$(ts) WOULD GEMINI ($1)"; return; fi
  echo "$(ts) gemini ($1)"
  input keyevent KEYCODE_WAKEUP
  wm dismiss-keyguard
  for i in 1 2 3 4 5 6 7 8 9 10; do dumpsys window 2>/dev/null | grep -q isKeyguardShowing=true || break; sleep 0.1; done
  input keyevent KEYCODE_VOICE_ASSIST
}
# A wake the desktop doesn't own lights the screen (no unlock: the word fires in conversation too), so you can see it
# heard you. Backgrounded: it never delays the mic. $D/NOSCREEN = off.
screen_on() { [ -e $D/NOSCREEN ] || input keyevent KEYCODE_WAKEUP & }
# DESK GATE (bars and why: gate.sh). Fails open: any missing reading → fire. On the home AP, close by, the listener is
# poked HELD first (mic open, no chirp), then magread (0.4-0.6 s) either cancels it silently or releases the chirp.
# So a docked wake never beeps, and a one-breath "computer, do X" still keeps the X. Away it is poked at once.
W=$(cmd wifi status 2>/dev/null)
kill -0 "$(cat $R/listen.pid 2>/dev/null)" 2>/dev/null || start_l
id=w$(date +%s)$$
if at_home; then
  if close; then
    poke "$id hold" || { gemini "listener not reading"; done_; }
    if magnet; then
      poke1 cancel
      echo "$(ts) skip: docked at desk ($M)"
      for i in 1 2 3 4 5 6 7 8 9 10; do [ -e $R/$id.json ] && break; sleep 0.1; done; rm -f $R/$id.json
      done_
    fi
    poke1 go
  else
    poke "$id" || { gemini "listener not reading"; done_; }
  fi
  screen_on
else
  # away = not on the home Wi-Fi → the desktop can refuse things that only make sense at home (e.g. "play …" on the TV).
  # No tunnel → raise it now: it is up in ~1-2 s, inside the time you are still talking, and the listener's 2.5 s
  # connect timeout is the backstop. wgaway.sh normally keeps it up away from home already.
  nd=
  if [ -n "$WG_TUNNEL" ] && [ -n "$WG_PHONE_IP" ] && ! ip -o addr show tun0 2>/dev/null | grep -qF " $WG_PHONE_IP/"; then nd=" tunnel raised"
    am broadcast -a com.wireguard.android.action.SET_TUNNEL_UP -n 'com.wireguard.android/.model.TunnelManager$IntentReceiver' \
      -e tunnel "$WG_TUNNEL" >/dev/null 2>&1
  fi
  # A blip is not away: the Wi-Fi scorer can drop a full-signal home link for 1-3 s (a "data stall" disconnect). My
  # phone once fired inside one of those and went out as away. wgaway.sh touches home.seen every 5 s on home Wi-Fi,
  # so home ≤ BLIP_S ago = still home: no away flag. Cost: the first BLIP_S after really leaving count as home.
  # Without wgaway.sh there is no home.seen, so every off-Wi-Fi wake counts as away.
  BLIP_S=15; hs=$(( $(date +%s) - $(stat -c %Y $D/home.seen 2>/dev/null || echo 0) ))
  if [ $hs -le $BLIP_S ]; then nd="$nd blip: home ${hs}s ago"; aw=; else aw=" away"; fi
  poke "$id$aw" || { gemini "listener not reading"; done_; }
  screen_on
fi
echo "$(ts) fire ($(echo "$W" | grep -m1 -oE 'RSSI: -?[0-9]+') $M$nd)"
# wait for its verdict: ≤9 s listening + 1.5 s cap grace + desktop (2.5 s connect / 30 s read)
for i in $(seq 1 450); do [ -e $R/$id.json ] && break; sleep 0.1; done
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
