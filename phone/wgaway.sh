#!/system/bin/sh
# OPTIONAL: keep the WireGuard tunnel up whenever the phone is off the home Wi-Fi, so the wake word (and alertd.sh)
# can always reach the desktop. Root loop, started by service.d-stwake.sh if this file is on the phone.
# Polls every 5 s (`ip monitor` printed nothing on my ROM; a poll costs a few ms) and acts only on a mismatch:
# not on HOME_SSID and no tunnel → UP; on HOME_SSID with the tunnel up for HOLD_S → DOWN.
#
# Needs the official WireGuard app with a tunnel named $WG_TUNNEL, and its "allow remote control apps" setting on.
# Use a split tunnel (AllowedIPs = your home LAN and the VPN subnet only) so only home traffic rides it.
# On my ROM two vendor blocks silently ate the root broadcasts until they were lifted for WireGuard: the vendor's
# auto-launch list (not durable, so it is re-applied below; harmless where the service doesn't exist) and Android's
# background foreground-service start ban (the deviceidle whitelist, durable).
#
# Why UP at once but DOWN only after HOLD_S: the phone drops Wi-Fi two ways. (a) Full-signal blips: the Wi-Fi scorer
# calls the link bad (data stall) and disconnects for 1-3 s. (b) The real edge of the house: a weak 2.4 GHz link
# drops to cell and comes back minutes later, again and again. A plain poll tracked every flap. Staying up a little
# longer at home is harmless (the desktop answers over the tunnel at home too).
# Each poll on home Wi-Fi touches $D/home.seen: on-wake.sh tells a blip from a real leave by its age.
#
# Settings from $D/stwake.conf:
#   HOME_SSID=MyHomeWifi      your home Wi-Fi name
#   WG_TUNNEL=home            the tunnel's name in the WireGuard app
#   WG_PHONE_IP=...           the phone's Address from the tunnel config, without the /32 (how we tell it is up)
# Off: touch $D/WGAWAY_OFF (the loop then leaves the tunnel alone). Log: $D/wgaway.log.
D=/data/local/tmp/stwake; LOG=$D/wgaway.log
HOME_SSID=; WG_TUNNEL=; WG_PHONE_IP=
[ -f $D/stwake.conf ] && . $D/stwake.conf
[ -n "$HOME_SSID" ] && [ -n "$WG_TUNNEL" ] && [ -n "$WG_PHONE_IP" ] || { echo "wgaway: set HOME_SSID, WG_TUNNEL, WG_PHONE_IP in stwake.conf" >> $LOG; exit 0; }
RX='com.wireguard.android/.model.TunnelManager$IntentReceiver'
ts() { date '+%m-%d %H:%M:%S'; }
echo $$ > $D/wgaway.pid
dumpsys AutoLaunch -a com.wireguard.android >/dev/null 2>&1
dumpsys deviceidle whitelist +com.wireguard.android >/dev/null 2>&1
echo "$(ts) up pid=$$" >> $LOG
HOLD_S=45; POLL=5; since=; was=; lastup=
while :; do
  if [ ! -e $D/WGAWAY_OFF ]; then
    W=$(cmd wifi status 2>/dev/null); now=$(date +%s)
    if echo "$W" | grep -qF "SSID: \"$HOME_SSID\""; then home=1; touch $D/home.seen; since=${since:-$now}; else home=; since=; fi
    ip -o addr show tun0 2>/dev/null | grep -qF " $WG_PHONE_IP/" && tun=1 || tun=
    if [ "$home" != "$was" ]; then         # every edge, with the signal it happened at: the evidence for HOLD_S
      if [ -n "$home" ]; then echo "$(ts) home Wi-Fi ($(echo "$W" | grep -m1 -oE 'RSSI: -?[0-9]+') $(echo "$W" | grep -m1 -oE 'Frequency: [0-9]+'))" >> $LOG
      else echo "$(ts) off home Wi-Fi" >> $LOG; fi
    fi
    was=$home
    if [ -z "$home" ] && [ -z "$tun" ]; then
      # retry every 20 s, not every poll: with no signal (dead zone, airplane mode) the tunnel can't come up for hours
      if [ -z "$lastup" ] || [ $((now - lastup)) -ge 20 ]; then lastup=$now
        am broadcast -a com.wireguard.android.action.SET_TUNNEL_UP -n "$RX" -e tunnel "$WG_TUNNEL" >/dev/null 2>&1
        echo "$(ts) away, no tunnel → UP" >> $LOG
      fi
    elif [ -n "$home" ] && [ -n "$tun" ]; then
      if [ $((now - since)) -ge $HOLD_S ]; then
        am broadcast -a com.wireguard.android.action.SET_TUNNEL_DOWN -n "$RX" -e tunnel "$WG_TUNNEL" >/dev/null 2>&1
        echo "$(ts) home $((now - since)) s, tunnel up → DOWN" >> $LOG
      fi
    fi
  fi
  sleep $POLL
done
