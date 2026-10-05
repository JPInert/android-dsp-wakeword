#!/system/bin/sh
# Supervisor for the DSP wake word (root, started at boot by the KernelSU/Magisk service.d hook).
# Runs Wake forever; restarts it with backoff when it exits. ADSP GUARD: if the audio DSP's fatal-error
# count rose while Wake ran, unload is already done (Wake exits on ABORT) and we back off 30 min, so a bad
# model can never put the phone in a crash loop. Holds NO wakelock — the point is that the phone sleeps.
D=/data/local/tmp/stwake
MODEL=${MODEL:-user/computer.model}      # your UDK model (udkgen) or a GMM .uim; set it in $D/stwake.conf
THR=${THR:-70}
LOG=$D/wake.log
crashes() { dmesg | grep -c "remoteproc-adsp: fatal error"; }
echo $$ > $D/stwaked.pid
BACKOFF=10
while :; do
  [ -f $D/DISABLED ] && { sleep 60; continue; }
  # keep the log small: trim to the last 2000 lines past 4000
  [ -f $LOG ] && [ $(wc -l < $LOG) -gt 4000 ] && { tail -2000 $LOG > $LOG.tmp; mv $LOG.tmp $LOG; }
  rm -f $D/ABORT
  # per-run config (MODEL=, THR=) — lets a model/threshold switch take effect on the next restart
  [ -f $D/stwake.conf ] && . $D/stwake.conf
  BASE=$(crashes)
  echo "$(date '+%m-%d %H:%M:%S') supervisor: start Wake $MODEL thr=$THR (adsp crashes $BASE)" >> $LOG
  START=$(date +%s); CRASHED=
  LD_LIBRARY_PATH=$D/lib CLASSPATH=/system_ext/priv-app/QualcommVoiceActivation/QualcommVoiceActivation.apk:$D/stwake.jar \
    app_process /system/bin Wake $D/$MODEL $THR true full 0 0 >> $LOG 2>&1 &
  JP=$!; echo $JP > $D/wake.pid
  # crash guard while running: dmesg poll every 2 s costs ~nothing and only runs while the AP is awake
  while kill -0 $JP 2>/dev/null; do
    if [ "$(crashes)" -gt "$BASE" ]; then
      echo "$(date '+%m-%d %H:%M:%S') supervisor: ADSP CRASH while listening — stopping, 30 min backoff" >> $LOG
      touch $D/ABORT; sleep 3; kill $JP 2>/dev/null; sleep 2; kill -9 $JP 2>/dev/null
      BACKOFF=1800; CRASHED=1
    fi
    sleep 2
  done
  wait $JP; RC=$?
  RAN=$(( $(date +%s) - START ))
  # an ABORT we did not raise ourselves = a requested restart (conf change, `touch ABORT`): no backoff
  if [ -f $D/ABORT ] && [ -z "$CRASHED" ]; then
    echo "$(date '+%m-%d %H:%M:%S') supervisor: Wake exited rc=$RC after ${RAN}s (requested), restart in 2s" >> $LOG
    BACKOFF=10; sleep 2; continue
  fi
  [ $RAN -gt 600 ] && [ $BACKOFF -lt 1800 ] && BACKOFF=10       # it ran fine for a while: reset
  echo "$(date '+%m-%d %H:%M:%S') supervisor: Wake exited rc=$RC after ${RAN}s, retry in ${BACKOFF}s" >> $LOG
  sleep $BACKOFF
  [ $BACKOFF -lt 600 ] && BACKOFF=$((BACKOFF * 2))
  [ $BACKOFF -eq 1800 ] && BACKOFF=600
done
