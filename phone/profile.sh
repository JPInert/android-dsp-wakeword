#!/system/bin/sh
# On the phone, as root. TEMPORARILY force the QC_VOICE_UI (SVA) "low_power" capture profile to another one,
# by bind-mounting an edited copy of the audio HAL's resourcemanager XML over the vendor file in init's mount
# namespace, then restarting the audio HAL so it re-reads it. Nothing on disk under /vendor changes; a reboot
# or `profile.sh off` undoes it.   usage: profile.sh <PROFILE_NAME> | off | show
# Why: on my SoC the PDK engine crashed the ADSP whenever PAL dropped it into the screen-off low-power profile
# DUAL_MIC_16KHZ_16BIT_FFNS (trials t1/t2/t4); it ran clean, but deaf, in DUAL_MIC_48KHZ_16BIT_FFECNS (t3).
# The working GMM/UDK setup does NOT need this script. It is here for experiments only.
# The XML path and sound card name below are for my phone (Snapdragon "canoe", WSA884x amps): find yours.
D=/data/local/tmp/stwake
F=/vendor/etc/audio/sku_canoe/resourcemanager_canoe_mtp_wsa884x.xml
grep -q canoe-mtp-wsa884x /proc/asound/cards || { echo "sound card is not canoe-mtp-wsa884x — wrong XML, refusing"; exit 1; }
N="nsenter -t 1 -m --"
case "$1" in
  show) $N grep -n -A30 '<name>QC_VOICE_UI</name>' $F | grep low_power; $N grep " $F " /proc/mounts; exit 0 ;;
  off)  $N umount $F 2>/dev/null; echo "unmounted" ;;
  *)
    P=$1
    grep -q "capture_profile name=\"$P\"" $F || { echo "no capture_profile $P in $F"; exit 1; }
    $N umount $F 2>/dev/null
    cp $F $D/rm.xml
    # only the low_power line inside the QC_VOICE_UI stream_config
    awk -v P="$P" '/<name>QC_VOICE_UI<\/name>/{q=1} q && /<low_power /{sub(/capture_profile_handset="[^"]*"/,"capture_profile_handset=\"" P "\""); q=0} {print}' $F > $D/rm.xml.new
    diff $F $D/rm.xml.new | grep "^[-+] "
    [ "$(diff $F $D/rm.xml.new | grep -c '^+ ')" = 1 ] || { echo "edit did not change exactly one line, refusing"; exit 1; }
    mv $D/rm.xml.new $D/rm.xml; chmod 644 $D/rm.xml
    chcon --reference=$F $D/rm.xml 2>/dev/null || chcon u:object_r:vendor_configs_file:s0 $D/rm.xml
    $N mount --bind $D/rm.xml $F || { echo "bind mount failed"; exit 1; }
    ;;
esac
OLD=$(pidof audiohalservice.qti); kill $OLD
for i in 1 2 3 4 5 6 7 8 9 10; do sleep 1; NEW=$(pidof audiohalservice.qti); [ -n "$NEW" ] && [ "$NEW" != "$OLD" ] && break; done
echo "audio HAL $OLD -> $NEW"
$N grep -n -A30 '<name>QC_VOICE_UI</name>' $F | grep low_power
