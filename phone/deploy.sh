#!/bin/bash
# Desktop → phone: build, push the jar + scripts to /data/local/tmp/stwake, install the service.d boot hook,
# (re)start the supervisor. Assumes `ssh $PHONE` lands in a shell on the phone that can run `su` (I use Termux's
# sshd). The Qualcomm pieces must already be on the phone (README: "What you need to supply yourself").
#   PHONE=myphone ./deploy.sh
set -e
cd "$(dirname "$0")"
PHONE=${PHONE:-phone}
./build.sh >/dev/null
[ -x native/magread ] || native/build-native.sh
# gate.sh is required (on-wake.sh sources it); alertd.sh and wgaway.sh are optional: delete them from both lists
# below if you don't want desktop alerts on the phone or the VPN keeper.
scp -q out/stwake.jar native/magread gate.sh on-wake.sh stwaked.sh alertd.sh wgaway.sh service.d-stwake.sh "$PHONE":
if [ -d earcons ]; then ssh "$PHONE" mkdir -p earcons && scp -q earcons/*.wav "$PHONE":earcons/; fi   # optional cue sounds
ssh "$PHONE" 'su -c "
set -e
D=/data/local/tmp/stwake; H=\$(pwd)
for f in stwake.jar magread gate.sh on-wake.sh stwaked.sh alertd.sh wgaway.sh; do [ -f \$H/\$f ] || continue; cp \$H/\$f \$D/; done   # gate.sh before on-wake.sh: it sources it
if [ -d \$H/earcons ]; then mkdir -p \$D/earcons; cp \$H/earcons/*.wav \$D/earcons/; chmod 644 \$D/earcons/*.wav; fi
cp \$D/stwake.jar \$D/listen.jar.tmp && chmod 644 \$D/listen.jar.tmp && mv \$D/listen.jar.tmp \$D/listen.jar   # listener (uid 1000) — restart it: kill \$(cat /data/local/tmp/pv/run/listen.pid)
chmod 755 \$D/*.sh \$D/magread
mkdir -p /data/adb/service.d; cp \$H/service.d-stwake.sh /data/adb/service.d/stwake.sh; chmod 755 /data/adb/service.d/stwake.sh
[ -f \$D/stwake.conf ] || { echo MISSING \$D/stwake.conf; exit 1; }
. \$D/stwake.conf
for f in \$MODEL lib/libqvasml.so lib/libqvajni.so; do [ -e \$D/\$f ] || { echo MISSING \$D/\$f; exit 1; }; done
[ -f \$D/stwaked.pid ] && kill \$(cat \$D/stwaked.pid) 2>/dev/null || true
[ -f \$D/wake.pid ] && { touch \$D/ABORT; sleep 3; kill \$(cat \$D/wake.pid) 2>/dev/null || true; }
rm -f \$D/ABORT
nohup sh \$D/stwaked.sh >/dev/null 2>&1 &
# the optional loops: restart them on the new scripts (they read gate.sh / stwake.conf once, at start), and lift them
# out of this ssh shell cpuset/cpu groups into the root ones the boot-started loops sit in: a loop left in the Termux
# groups can get frozen. Kill the alertd curl too: the shell holds a SIGTERM until the 280 s long poll returns, and the
# old loop could then play an alert once more beside the new one.
for L in wgaway alertd; do
  [ -f \$D/\$L.pid ] && { P0=\$(cat \$D/\$L.pid); kill \$P0; pkill -P \$P0; } 2>/dev/null || true
  rm -f \$D/\$L.pid
  [ -f \$D/\$L.sh ] || continue
  setsid nohup sh \$D/\$L.sh >/dev/null 2>&1 < /dev/null &
  sleep 1; P=\$(cat \$D/\$L.pid 2>/dev/null) && { echo \$P > /dev/cpuset/tasks; echo \$P > /dev/cpuctl/tasks; } 2>/dev/null || true
done
sleep 8; tail -3 \$D/wake.log
"'
