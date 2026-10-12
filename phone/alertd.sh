#!/system/bin/sh
# OPTIONAL: desktop alerts spoken on the phone when it is OFF the dock. My desktop speaks camera alerts in the room;
# with this loop the phone speaks them too whenever it isn't sitting on the desk charger, at home or away.
# Root loop, started by service.d-stwake.sh if this file is on the phone.
#
# The desktop usually can't reach the phone (no route back through the VPN, no server on the phone), so the phone
# asks: a long poll, POST <desk.url>/alerts {since} → 200 audio/wav (headers X-Seq, X-Text) the moment an alert is
# queued, or 204 after the desktop's hold time (desk_stub.py documents the contract and answers 204).
# Then the SAME dock gate as the wake word (gate.sh): docked → drop (the desk speaker has it), loose/away → the listener
# chimes and plays it (FIFO "<id> play:<wav>", USAGE_MEDIA = the volume rocker's stream). It waits for an open mic to
# close first, so an alert is never heard as your words.
# Away, it rides the VPN tunnel that wgaway.sh keeps up.
# Off: touch $D/ALERTD_OFF. Log: $D/alertd.log.
D=/data/local/tmp/stwake; PV=/data/local/tmp/pv; R=$PV/run; LOG=$D/alertd.log
. $D/gate.sh
echo $$ > $D/alertd.pid
exec >> $LOG 2>&1
echo "$(ts) up pid=$$"
AUTH=$D/alertd.auth          # the bearer header from a 0600 file, never on curl's argv (ps shows argv to everyone)
since=0; fails=0
while :; do
  if [ -e $D/ALERTD_OFF ]; then sleep 60; continue; fi
  URL="$(cat $PV/desk.url 2>/dev/null)/alerts"
  ( umask 077; printf 'Authorization: Bearer %s\n' "$(cat $PV/voice.token 2>/dev/null)" > $AUTH )
  mkdir -p $R; chown 1000:1000 $R
  f=$R/alert.wav.tmp; h=$R/alert.hdr; rm -f $f $h
  code=$(curl -s -m 280 --connect-timeout 5 -H @$AUTH -H 'Content-Type: application/json' \
         -d "{\"since\": $since}" -D $h -o $f -w '%{http_code}' "$URL")
  case "$code" in
  200)
    echo "alertd 30000000000" > /sys/power/wake_lock      # gate + chime + a few seconds of speech; expires itself
    fails=0
    seq=$(grep -i '^x-seq:' $h | tr -dc 0-9)
    text=$(grep -i '^x-text:' $h | cut -d' ' -f2- | tr -d '\r')
    [ -n "$seq" ] && since=$seq
    dock_state
    if [ "$G" = docked ]; then
      echo "$(ts) $seq skip: docked ($M) $text"; rm -f $f
      echo alertd > /sys/power/wake_unlock
    else
      id=a$seq; w=$R/$id.wav
      mv $f $w; chown 1000:1000 $w; chmod 644 $w
      if poke "$id play:$w"; then echo "$(ts) $seq play: $G ($(echo "$W" | grep -m1 -oE 'RSSI: -?[0-9]+') $M) $text"
      else echo "$(ts) $seq FAILED: listener not reading ($G) $text"; rm -f $w; fi
    fi ;;
  204)
    fails=0
    seq=$(grep -i '^x-seq:' $h | tr -dc 0-9); [ -n "$seq" ] && [ "$seq" -gt "$since" ] && since=$seq ;;
  *)
    # desktop down / no route (leaving home, tunnel not up yet): back off 5 → 60 s; log the first and every 20th
    fails=$((fails + 1))
    [ $fails -eq 1 ] || [ $((fails % 20)) -eq 0 ] && echo "$(ts) poll failed (http $code, $fails in a row)"
    s=$((fails * 5)); [ $s -gt 60 ] && s=60; sleep $s ;;
  esac
done
