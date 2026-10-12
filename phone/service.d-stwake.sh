#!/system/bin/sh
# KernelSU / Magisk service.d hook (installed as /data/adb/service.d/stwake.sh): start the DSP wake-word supervisor
# once the system is up. Remove this file (or touch /data/local/tmp/stwake/DISABLED) to turn it off.
D=/data/local/tmp/stwake
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
sleep 30
nohup sh $D/stwaked.sh > /dev/null 2>&1 &
# optional: keep the VPN up whenever the phone is off the home Wi-Fi (wgaway.sh)
[ -f $D/wgaway.sh ] && nohup sh $D/wgaway.sh > /dev/null 2>&1 &
# optional: desktop alerts spoken on the phone when it is off the dock (alertd.sh)
[ -f $D/alertd.sh ] && nohup sh $D/alertd.sh > /dev/null 2>&1 &
