#!/system/bin/sh
# KernelSU / Magisk service.d hook (installed as /data/adb/service.d/stwake.sh): start the DSP wake-word supervisor
# once the system is up. Remove this file (or touch /data/local/tmp/stwake/DISABLED) to turn it off.
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
sleep 30
nohup sh /data/local/tmp/stwake/stwaked.sh > /dev/null 2>&1 &
