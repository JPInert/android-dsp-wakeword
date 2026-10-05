#!/system/bin/sh
# On the phone, as root. One GUARDED trial of the DSP wake word, with an ADSP crash watchdog.
#   test.sh <name> <model.uim> <thresholds> <capture true|false> <opaque full|none> <holdSeconds> <stagePause>
# The watchdog compares the ADSP fatal-error COUNT in dmesg to its value at start; on the first increase it
# touches ABORT (Wake stops/unloads/detaches within 200 ms), then keeps counting for 30 s to see whether
# the crash loop persists without us. Everything goes to $D/test-<name>.log. Detached from ssh on purpose.
# AUTO-REBOOT: on my phone a PDK crash loop did not clear without a reboot, and once needed a long power
# press. Reboot when crashes keep coming 8 s after our unload, or Wake ignores ABORT for 10 s.
D=/data/local/tmp/stwake
NAME=$1; shift
LOG=$D/test-$NAME.log
crashes() { dmesg | grep -c "remoteproc-adsp: fatal error"; }
ts() { date +%H:%M:%S.%3N 2>/dev/null || date +%T; }
rm -f $D/ABORT
# hold a kernel wakelock for the whole trial: without it the phone suspends and every sleep (and this
# watchdog) stretches by minutes — in my first trial the 8 s stage pauses took 82 s and 106 s.
echo stwake-test > /sys/power/wake_lock
trap 'echo stwake-test > /sys/power/wake_unlock' EXIT
BASE=$(crashes)
echo "$(ts) TRIAL $NAME args: $*  base crashes=$BASE" > $LOG
[ "$BASE" -ge 0 ] 2>/dev/null || { echo "$(ts) cannot read crash count, refusing" >> $LOG; exit 1; }
cd $D
LD_LIBRARY_PATH=$D/lib CLASSPATH=/system_ext/priv-app/QualcommVoiceActivation/QualcommVoiceActivation.apk:$D/stwake.jar \
  app_process /system/bin Wake "$@" >> $LOG 2>&1 &
JP=$!
echo $JP > $D/wake.pid
reboot_now() {
  echo "$(ts) AUTO-REBOOT: $1" >> $LOG
  logcat -d -b all > $D/test-$NAME.logcat 2>&1; dmesg > $D/test-$NAME.dmesg 2>&1   # gone after the reboot
  sync
  svc power reboot >/dev/null 2>&1; sleep 5; reboot
}
CRASHED=0; WAITED=0
while kill -0 $JP 2>/dev/null; do
  N=$(crashes)
  if [ "$N" -gt "$BASE" ] && [ $CRASHED = 0 ]; then
    CRASHED=1; touch $D/ABORT
    echo "$(ts) WATCHDOG: ADSP crash ($BASE -> $N) — ABORT" >> $LOG
  fi
  if [ $CRASHED = 1 ]; then
    WAITED=$((WAITED+1)); [ $WAITED -gt 33 ] && { kill -9 $JP; reboot_now "Wake ignored ABORT for 10 s"; }
  fi
  sleep 0.3
done
wait $JP; RC=$?
echo "$(ts) Wake exited rc=$RC" >> $LOG
A=$(crashes); sleep 8; B=$(crashes)
[ "$B" -gt "$A" ] && reboot_now "crash loop persists after unload ($A -> $B in 8 s)"
sleep 22; B=$(crashes)
[ "$B" -gt "$A" ] && reboot_now "crash loop persists after unload ($A -> $B in 30 s)"
echo "$(ts) after exit: crashes $A -> $B over 30 s (total since start $((B-BASE)))" >> $LOG
[ "$B" -gt "$A" ] && echo "$(ts) RESULT: CRASH LOOP PERSISTS WITHOUT US — needs reboot" >> $LOG
[ "$B" -eq "$BASE" ] && echo "$(ts) RESULT: CLEAN (0 ADSP crashes)" >> $LOG
[ "$B" -gt "$BASE" ] && [ "$B" -eq "$A" ] && echo "$(ts) RESULT: crashed $((A-BASE))x, loop STOPPED after unload" >> $LOG
rm -f $D/wake.pid
echo "$(ts) DONE" >> $LOG
