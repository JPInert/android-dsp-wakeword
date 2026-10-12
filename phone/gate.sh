#!/system/bin/sh
# Sourced by on-wake.sh (the wake word) and alertd.sh (optional desktop alerts): the desk dock gate and the listener
# helpers. Needs D, PV, R set. One copy so the two can't drift: change the bars here (or in stwake.conf).
#
# DESK GATE: when the phone sits on a magnetic (MagSafe-style) charger at the desk, the desktop's own microphone owns
# the wake word and its speaker plays the alerts, so the phone stays quiet. Docked = home AP close by AND a magnet on
# the back. Car mount is off home Wi-Fi → away. A home AP with two radios needs BOTH BSSIDs in HOME_BSSID_RE: the
# phone hops between them, and matching only one let docked wakes through.
#
# The magnet reading DRIFTS (README, "the magnet lesson"): magread's anom = |raw − the phone's auto-calibration bias|,
# and the auto-calibration slowly learns the dock magnet as part of the bias, so the docked reading falls over days
# on the base (mine fell from 98-105 µT to 68-72 µT in seven days). Re-measure if docked wakes start leaking.
#
# Settings come from $D/stwake.conf (the same file the supervisor reads):
#   HOME_BSSID_RE='aa:bb:cc:dd:ee:(01|02)'  your home access point's BSSIDs (both radios), as an ERE. Empty = gate off.
#   DOCK_ANOM=64                             magnetometer anomaly (µT) above which the phone counts as docked
#   HOME_RSSI_MAX=72                         only gate when the home AP is stronger than -72 dBm
HOME_BSSID_RE=; DOCK_ANOM=64; HOME_RSSI_MAX=72
[ -f $D/stwake.conf ] && . $D/stwake.conf
ts() { date '+%m-%d %H:%M:%S'; }
# at_home → true when `cmd wifi status` (in W) shows one of the home BSSIDs
at_home() { [ -n "$HOME_BSSID_RE" ] && echo "$W" | grep -qE "BSSID: ($HOME_BSSID_RE)"; }
# close → true when the home AP is stronger than -HOME_RSSI_MAX dBm
close() { [ "$(echo "$W" | grep -m1 -oE 'RSSI: -?[0-9]+' | grep -oE '[0-9]+$')" -lt "$HOME_RSSI_MAX" ] 2>/dev/null; }
# magnet → true when magread says docked; the reading goes to M for the log. Costs 0.4-0.6 s (sensor start-up).
magnet() {
  M=$($D/magread 1 2>/dev/null)
  A=$(echo "$M" | grep -oE '^anom=[0-9]+' | cut -d= -f2)
  [ -n "$A" ] && [ "$A" -gt "$DOCK_ANOM" ]
}
# dock_state → G = docked | loose | away, readings in W and M. Fails open: a missing reading is never "docked".
dock_state() {
  W=$(cmd wifi status 2>/dev/null); M=
  at_home || { G=away; return; }
  close || { G=loose; return; }
  if magnet; then G=docked; else G=loose; fi
  # Ground truth for the log, NOT used in the decision: labels that are the gate's own opinion can never prove the
  # gate wrong. wl=1 = on a wireless charger (only useful if your dock actually charges).
  M="$M wl=$(dumpsys battery 2>/dev/null | grep -m1 'Wireless powered' | grep -q true && echo 1 || echo 0)"
}
# The listener: lazy-start it if it isn't running (the first wake after a reboot pays ~1 s), then poke.
# Start it from a ROOT process tree (Wake → on-wake.sh, or service.d → alertd.sh), never from an ssh shell: one started
# from Termux's sshd sits in Termux's app cgroup, Android's freezer stops it, `kill -0` still passes and the FIFO is
# never read. That happened to me once and every wake fell through to the stock assistant for two days. So a poke
# that isn't read within 1 s restarts the listener once (start_l) and tries again.
start_l() {
  [ -n "$1" ] && { kill -9 "$(cat $R/listen.pid 2>/dev/null)" 2>/dev/null; rm -f $R/listen.pid; }
  mkdir -p $R; chown 1000:1000 $R
  # wait for a NEW "listener up" line: matching on the pid file matched old log lines and never waited
  n0=$(wc -l < $PV/listen.log 2>/dev/null || echo 0)
  nohup setsid su 1000 -c "CLASSPATH=$D/listen.jar exec app_process /system/bin Listen" >> $PV/listen.log 2>&1 < /dev/null &
  for i in $(seq 1 50); do tail -n +$((n0 + 1)) $PV/listen.log 2>/dev/null | grep -q "listener up" && break; sleep 0.1; done
  echo "$(ts) listener started${1:+ ($1)}"
}
poke1() { timeout 1 sh -c "echo $1 > $R/poke" 2>/dev/null; }
poke() { poke1 "$1" || { start_l "not reading"; poke1 "$1"; }; }
