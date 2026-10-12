import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * DSP wake word without an app: load a Qualcomm SVA first-stage model (a GMM .uim or a UDK model from udkgen,
 * re-packed by QVA's own codec, see Codec) straight into the sound-trigger HAL from a root app_process, and
 * log every detection. The call sequence and the opaque config layout follow what Qualcomm Voice Activation
 * 6.1.4 sends to the HAL; they were confirmed against the PAL logs on the phone.
 *
 * usage: Wake <model.uim> <thresholds csv, one per keyword> [captureRequested true|false]
 *             [opaque full|none] [holdSeconds, 0 = forever] [stagePauseSeconds]
 * STAGED for crash bisection: attach → pause → load → pause → start, logging "STAGE x" before each, and
 * polling ABORT (touched by test.sh's crash watchdog) every 200 ms → stop/unload/detach/exit 9.
 */
public class Wake {
    static final UUID VENDOR = UUID.fromString("68ab2d40-e860-11e3-95ef-0002a5d5c51b");   // QC_VOICE_UI
    static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");
    static void log(String s) { System.out.println(TS.format(new Date()) + " " + s); System.out.flush(); }

    /** QVA opaque RecognitionConfig data: CONFIDENCE_LEVELS item then HISTORY_BUFFER_CONFIG item.
     *  Every struct is written into a FIXED-size slot (QVA pads with zeros), little-endian. */
    static byte[] opaque(int[] thr) {
        final int KW = 5 * 10 + 5, MODEL = KW * 10 + 8, INFO = MODEL * 10 + 8;   // 55, 558, 5588
        ByteBuffer b = ByteBuffer.allocate(8 + INFO + 8 + 12).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0).putInt(INFO);                       // Header{CONFIDENCE_LEVELS, payload size}
        int info = b.position();
        b.putInt(1);                                    // ConfidenceLevelInfo.version
        b.putInt(1);                                    //   model count
        int model = b.position();
        b.putInt(1);                                    //   ModelId SML_ID_SVA_F_STAGE
        b.putInt(thr.length);                           //   keyword count
        for (int t : thr) {
            int kw = b.position();
            b.put((byte) (t & 0xff));                   //     KeywordLevel.level
            b.putInt(0);                                //     user count
            b.position(kw + KW);
        }
        b.position(model + MODEL);
        b.position(info + INFO);
        b.putInt(1).putInt(12);                         // Header{HISTORY_BUFFER_CONFIG, 12}
        b.putInt(2).putInt(2000).putInt(1000);          // version, hist ms, preroll ms
        return b.array();
    }

    static Class<?> c(String n) throws ClassNotFoundException { return Class.forName("android.hardware.soundtrigger.SoundTrigger$" + n); }

    static Constructor<?> ctor(Class<?> k, Class<?>... p) throws NoSuchMethodException {
        try { return k.getConstructor(p); }
        catch (NoSuchMethodException e) {
            for (Constructor<?> x : k.getConstructors()) log("  have " + x);
            throw e;
        }
    }

    static final String ABORT = "/data/local/tmp/stwake/ABORT";
    static boolean aborted() { return new java.io.File(ABORT).exists(); }
    static void pause(int s) throws InterruptedException {
        for (int i = 0; i < s * 5; i++) { if (aborted()) return; Thread.sleep(200); }
    }

    public static void main(String[] a) throws Throwable {
        String uim = a[0];
        int[] thr = Arrays.stream(a[1].split(",")).mapToInt(Integer::parseInt).toArray();
        boolean capture = a.length > 2 && Boolean.parseBoolean(a[2]);
        boolean fullOpaque = a.length <= 3 || !a[3].equals("none");
        int hold = a.length > 4 ? Integer.parseInt(a[4]) : 0;
        int stagePause = a.length > 5 ? Integer.parseInt(a[5]) : 0;
        android.os.Looper.prepareMainLooper();

        Codec.loadLibs();
        byte[] data = Codec.firstStage(uim);

        Class<?> kp = c("Keyphrase"), ksm = c("KeyphraseSoundModel"), rc = c("RecognitionConfig"),
                 kre = c("KeyphraseRecognitionExtra"), cl = c("ConfidenceLevel"), sm = c("SoundModel");
        Object keyphrase = ctor(kp, int.class, int.class, Locale.class, String.class, int[].class)
                .newInstance(0, 1, Locale.forLanguageTag("en-US"), "", new int[]{0});
        Object kpa = Array.newInstance(kp, 1); Array.set(kpa, 0, keyphrase);
        UUID modelUuid = UUID.nameUUIDFromBytes("stwake-phoneunlock".getBytes());
        Object model = ctor(ksm, UUID.class, UUID.class, byte[].class, kpa.getClass())
                .newInstance(modelUuid, VENDOR, data, kpa);

        Object extra = ctor(kre, int.class, int.class, int.class, Array.newInstance(cl, 0).getClass())
                .newInstance(0, 1, thr[0], Array.newInstance(cl, 0));
        Object ea = Array.newInstance(kre, 1); Array.set(ea, 0, extra);
        Object config = ctor(rc, boolean.class, boolean.class, ea.getClass(), byte[].class)
                .newInstance(capture, false, ea, fullOpaque ? opaque(thr) : new byte[0]);

        Class<?> st = Class.forName("android.hardware.soundtrigger.SoundTrigger");
        Class<?> idC = Class.forName("android.media.permission.Identity");
        Class<?> lis = c("StatusListener");
        Object id = idC.getConstructor().newInstance();
        idC.getField("uid").setInt(id, android.os.Process.myUid());
        idC.getField("pid").setInt(id, android.os.Process.myPid());
        idC.getField("packageName").set(id, "com.android.shell");

        final Object[] modRef = new Object[1];
        final int[] handle = new int[1];
        Object listener = Proxy.newProxyInstance(lis.getClassLoader(), new Class<?>[]{lis}, (p, m, args) -> {
            switch (m.getName()) {
                case "onRecognition": onRecognition(args[0], modRef[0], handle[0], config); break;
                case "onServiceDied": case "onModelUnloaded":
                    log("cb " + m.getName() + " — exiting for the supervisor to restart"); System.exit(5); break;
                default: log("cb " + m.getName() + " " + (args == null ? "" : Arrays.toString(args)));
            }
            return null;
        });
        // The sound-trigger middleware refuses an attach from a client that hasn't listed the modules first ("Client
        // must call listModules() prior to attaching" in `dumpsys soundtrigger_middleware`). After a reboot that meant
        // a null attach on every retry until some other app happened to list them: the word was dead for about half an
        // hour after boot. So list them ourselves first.
        try {
            java.util.ArrayList<Object> mods = new java.util.ArrayList<>();
            Object lr0 = st.getMethod("listModulesAsOriginator", java.util.ArrayList.class, idC).invoke(null, mods, id);
            log("listModules rc=" + lr0 + " n=" + mods.size());
        } catch (Throwable t) { log("listModules: " + t); }
        log("STAGE attach");
        Object mod = st.getMethod("attachModuleAsOriginator", int.class, lis, android.os.Handler.class, idC)
                .invoke(null, 0, listener, new android.os.Handler(android.os.Looper.getMainLooper()), id);
        modRef[0] = mod;
        log("attached " + mod);
        if (mod == null) { log("attach returned null - exiting for the supervisor to retry"); System.exit(6); }
        pause(stagePause);
        if (aborted()) { log("ABORT before load"); mod.getClass().getMethod("detach").invoke(mod); System.exit(9); }
        log("STAGE load");
        int lr = (int) mod.getClass().getMethod("loadSoundModel", sm, int[].class).invoke(mod, model, handle);
        log("loadSoundModel rc=" + lr + " handle=" + handle[0]);
        if (lr != 0) { mod.getClass().getMethod("detach").invoke(mod); System.exit(2); }
        pause(stagePause);
        if (aborted()) { log("ABORT before start"); mod.getClass().getMethod("unloadSoundModel", int.class).invoke(mod, handle[0]); mod.getClass().getMethod("detach").invoke(mod); System.exit(9); }
        log("STAGE start");
        int sr = (int) mod.getClass().getMethod("startRecognition", int.class, rc).invoke(mod, handle[0], config);
        log("startRecognition rc=" + sr + " thresholds=" + Arrays.toString(thr) + " capture=" + capture);
        if (sr != 0) { mod.getClass().getMethod("unloadSoundModel", int.class).invoke(mod, handle[0]); mod.getClass().getMethod("detach").invoke(mod); System.exit(3); }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                mod.getClass().getMethod("stopRecognition", int.class).invoke(mod, handle[0]);
                mod.getClass().getMethod("unloadSoundModel", int.class).invoke(mod, handle[0]);
                mod.getClass().getMethod("detach").invoke(mod);
                log("stopped, unloaded, detached");
            } catch (Throwable t) { log("shutdown: " + t); }
        }));
        armedAt = System.currentTimeMillis();
        log("STAGE listening");
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final long until = hold > 0 ? System.currentTimeMillis() + hold * 1000L : Long.MAX_VALUE;
        h.post(new Runnable() { public void run() {
            boolean ab = aborted();
            if (ab || System.currentTimeMillis() > until) {
                log(ab ? "ABORT while listening" : "hold time over");
                System.exit(ab ? 9 : 0);                 // shutdown hook stops + unloads + detaches
            }
            h.postDelayed(this, 1000);
        }});
        android.os.Looper.loop();
    }

    static final String ON_WAKE = "/data/local/tmp/stwake/on-wake.sh";
    static long armedAt = 0, lastFire = 0;

    /** Run ON_WAKE (listener → desktop, or the stock assistant on a miss), detached, unless this is the re-arm
     *  echo (seen once: a fire 1.1 s after startRecognition, nobody spoke) or within 4 s of the last fire. */
    static void fire(String why) {
        long now = System.currentTimeMillis();
        if (now - armedAt < 1500) { log("ignored (" + (now - armedAt) + " ms after arming) " + why); return; }
        if (now - lastFire < 4000) { log("ignored (debounce) " + why); return; }
        lastFire = now;
        if (new java.io.File("/data/local/tmp/stwake/NOFIRE").exists()) { log("WOULD FIRE (NOFIRE set) " + why); return; }
        try { new ProcessBuilder("sh", ON_WAKE).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(new java.io.File("/data/local/tmp/stwake/on-wake.log"))).start();
              log("FIRED " + why); }
        catch (Throwable t) { log("on-wake failed: " + t); }
    }

    /** Log which keyword fired (first non-zero level in the returned CONFIDENCE_LEVELS), then re-arm. */
    static void onRecognition(Object ev, Object mod, int handle, Object config) {
        try {
            int status = ev.getClass().getField("status").getInt(ev);
            byte[] d = (byte[]) ev.getClass().getField("data").get(ev);
            StringBuilder lv = new StringBuilder();
            if (d != null && d.length >= 8) {
                ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
                while (b.remaining() >= 8) {
                    int key = b.getInt(), size = b.getInt(), start = b.position();
                    if (key == 0 && size >= 16) {                       // CONFIDENCE_LEVELS
                        b.getInt(); int models = b.getInt();
                        for (int mi = 0; mi < models; mi++) {
                            int ms = b.position(); int mid = b.getInt(); int kws = b.getInt();
                            lv.append("model").append(mid).append("=[");
                            for (int k = 0; k < kws; k++) { int ks = b.position(); lv.append(k == 0 ? "" : ",").append(b.get()); b.position(ks + 55); }
                            lv.append("] ");
                            b.position(ms + 558);
                        }
                    } else if (key == 2 && size >= 12) {                // KEYWORD_INDICES
                        lv.append("kwidx v").append(b.getInt()).append(" ").append(b.getInt()).append("-").append(b.getInt()).append(" ");
                    }
                    b.position(Math.min(start + size, b.limit()));
                }
            }
            log("RECOGNITION status=" + status + " data=" + (d == null ? 0 : d.length) + "B " + lv);
            if (status == 0) fire(lv.toString().trim());
            if (status == 0 || status == 1) {
                int r = (int) mod.getClass().getMethod("startRecognition", int.class, config.getClass()).invoke(mod, handle, config);
                armedAt = System.currentTimeMillis();
                log("restart rc=" + r);
                if (r != 0) { log("re-arm failed — exiting for the supervisor"); System.exit(6); }
            }
        } catch (Throwable t) { log("onRecognition: " + t); }
    }
}
