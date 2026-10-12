import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * The phone's "computer" → your desktop assistant, no app. Resident, uid 1000: Android's ON-DEVICE
 * recogniser refuses uid 0 ("Cannot register attribution source for package:android from uid:0").
 *
 *   su 1000 -c "CLASSPATH=…/stwake.jar app_process /system/bin Listen [now]"
 *
 * Waits on the FIFO RUN/poke (root's on-wake.sh writes a request id; "<id> text:<words>" = dry run, no mic), keeps the recogniser warm, and per poke:
 * startListening → FINAL text → POST {text} to the desktop endpoint (pv/desk.url) → writes RUN/<id>.json for on-wake.sh:
 *   {"id", "heard", "miss", "why", "said":[…], "secs_listen", "secs_desk"}
 * miss=true + heard → the desktop had nothing (or was unreachable): on-wake.sh opens the stock assistant.
 * A hit may carry "pending": <job id> = a slow job the desktop runs in the background; the phone asks for its outcome
 * (POST <desk.url>/pending) and speaks it. Other FIFO lines: "go"/"cancel" (the dock gate's verdict, see on-wake.sh)
 * and "<id> play:<wav>" (a desktop alert to play, see alertd.sh).
 * why=no-speech → nobody spoke after the wake word.
 * "now" = also listen once at start (the lazy-start path: on-wake.sh found no listener running).
 * Never holds the mic between requests: the recogniser's session ends on FINAL/error, so the DSP is back in LPI.
 */
public class Listen {
    static final String RUN = "/data/local/tmp/pv/run", AUTH_FILE = "/data/local/tmp/pv/voice.token";   // the shared bearer token
    /** One line, e.g. http://desktop.example:7783/phone — any address the phone can reach (LAN, or your own VPN). */
    static final String DESK_URL_FILE = "/data/local/tmp/pv/desk.url";
    static String deskUrl() throws IOException {
        return new String(Files.readAllBytes(Paths.get(DESK_URL_FILE)), StandardCharsets.UTF_8).trim();
    }
    static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");
    static void log(String s) { System.out.println(TS.format(new Date()) + " " + s); System.out.flush(); }

    static Context ctx;
    static Handler main;
    static SpeechRecognizer rec;
    static String curId;
    static long tPoke, tFinal;
    static final List<String> queue = new ArrayList<>();

    public static void main(String[] a) throws Throwable {
        Looper.prepareMainLooper();
        main = new Handler(Looper.getMainLooper());
        Class<?> at = Class.forName("android.app.ActivityThread");
        ctx = (Context) at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
        new File(RUN).mkdirs();
        Files.write(Paths.get(RUN, "listen.pid"), String.valueOf(android.os.Process.myPid()).getBytes());
        File fifo = new File(RUN, "poke");
        if (!fifo.exists()) Runtime.getRuntime().exec(new String[]{"mkfifo", "-m", "622", fifo.getPath()}).waitFor();
        log("listener up pid=" + android.os.Process.myPid() + " uid=" + android.os.Process.myUid()
                + " onDevice=" + SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx));
        if (a.length > 0 && a[0].equals("now")) poke("boot" + System.currentTimeMillis());
        Thread reader = new Thread(() -> {
            while (true) {
                try (BufferedReader r = new BufferedReader(new FileReader(fifo))) {   // blocks until a writer opens
                    String line;
                    while ((line = r.readLine()) != null) {
                        String id = line.trim();
                        // "<id> text:<words>" skips the recogniser — a dry run of everything after it, no voice needed
                        int k = id.indexOf(" text:");
                        if (k > 0) { String rid = id.substring(0, k), t = id.substring(k + 6);
                            tFinal = System.currentTimeMillis(); new Thread(() -> desk(rid, t, 0, false)).start(); continue; }
                        if (id.equals("cancel")) { main.post(Listen::cancel); continue; }   // on-wake.sh: docked at the desk
                        // "<id> play:<wav>" (alertd.sh): an alert the desktop rendered: chime, then the WAV
                        int p = id.indexOf(" play:");
                        if (p > 0) { String rid = id.substring(0, p), f = id.substring(p + 6); new Thread(() -> alert(rid, f)).start(); continue; }
                        if (id.equals("go")) { main.post(Listen::go); continue; }           // on-wake.sh: held, and not docked
                        if (!id.isEmpty()) main.post(() -> poke(id));
                    }
                } catch (IOException e) { log("fifo: " + e); try { Thread.sleep(500); } catch (InterruptedException x) { } }
            }
        });
        reader.setDaemon(true);
        reader.start();
        Looper.loop();
    }

    static void poke(String line) {
        // "<id> [away] [nodesk] [hold]" (on-wake.sh): away = off the home Wi-Fi; nodesk = away with no VPN, so words
        // are a miss at once instead of a 2.5 s connect timeout; hold = the mic opens NOW but no chirp until "go".
        // The dock gate (magread, ~0.5 s) runs meanwhile, and "cancel" drops a docked wake silently. Opening the mic
        // first is what lets a one-breath "computer, do X" keep the X.
        List<String> f = Arrays.asList(line.split(" "));
        String id = f.get(0);
        if (curId != null) { log("busy with " + curId + " — dropped " + id); write(id, "", "busy", 0, 0); return; }
        curId = id; curAway = f.contains("away"); curNoDesk = f.contains("nodesk"); held = f.contains("hold");
        ready = false; tPoke = System.currentTimeMillis();
        listen(id);              // the chirp plays at onReadyForSpeech (see the listener)
    }

    static boolean curAway, curNoDesk, held, ready;

    /** The gate says not docked: cue now if the mic is already open, else onReadyForSpeech will. */
    static void go() {
        if (curId == null || !held) return;
        held = false;
        log(curId + " go +" + (System.currentTimeMillis() - tPoke) + "ms");
        if (ready) { earcon("wake", new int[]{880}, 120); buzz(60); }
    }

    static void listen(String id) {
        if (rec == null) { rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx); rec.setRecognitionListener(listener); }
        Intent in = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        rec.startListening(in);
        gotWords = false; maxRms = -100;
        log(id + " listening");
        // No WORDS by NOWORDS_MS = nobody is talking to us. The recogniser's own "speech" detector takes room noise
        // as speech and then sits to the cap: one silent wake took 9 s to the deny tone with the chirp already
        // moved out of the mic window, while a quiet-room one ended at 5 s.
        main.postDelayed(() -> { if (id.equals(curId) && !gotWords) { log(id + " no words in " + NOWORDS_MS + "ms (max rms " + maxRms + " dB)");
            rec.cancel(); curId = null; earcon("miss", new int[]{660, 440}, 110);
            write(id, "", "no-speech", (System.currentTimeMillis() - tPoke) / 1000.0, 0); } }, NOWORDS_MS);
        // A noisy car/TV keeps the recogniser "hearing speech": one no-speech poke ran 11.7 s. Cap it: at
        // MAX_MS ask for what it has (onResults), and if that never comes, cancel and call it a miss.
        main.postDelayed(() -> { if (id.equals(curId)) { log(id + " cap — stopListening"); rec.stopListening(); } }, MAX_MS);
        main.postDelayed(() -> { if (id.equals(curId)) { log(id + " cap — cancel"); rec.cancel(); curId = null;
            write(id, "", "timeout", (System.currentTimeMillis() - tPoke) / 1000.0, 0); } }, MAX_MS + 1500);
    }
    static final int MAX_MS = 9000, NOWORDS_MS = 4000;
    static volatile boolean gotWords;
    static volatile float maxRms;

    /** The desktop owns the wake word when the phone is docked there: drop the session silently, no tone. */
    static void cancel() {
        String id = curId; curId = null;
        if (id == null) return;
        if (rec != null) rec.cancel();                     // may still be in the chirp, before listen()
        log(id + " cancelled");
        write(id, "", "cancelled", (System.currentTimeMillis() - tPoke) / 1000.0, 0);
    }

    static final RecognitionListener listener = new RecognitionListener() {
        public void onReadyForSpeech(Bundle b) {
            // Cue the moment the mic is open. It once moved BEFORE the mic to keep the recogniser from taking it as
            // speech, but that wasn't why silence sat to 9 s (NOWORDS_MS fixed that), so it is back here: +0.45 s faster.
            // Held (at home, dock gate still running): stay silent until "go".
            log(curId + " ready +" + (System.currentTimeMillis() - tPoke) + "ms" + (held ? " (held)" : ""));
            ready = true;
            if (!held) { earcon("wake", new int[]{880}, 120); buzz(60); }
        }
        public void onBeginningOfSpeech() { }
        public void onRmsChanged(float v) { if (v > maxRms) maxRms = v; }   // diagnosis: is the mic hearing anything
        public void onBufferReceived(byte[] b) { }
        public void onEndOfSpeech() { }
        public void onPartialResults(Bundle b) {
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty() && !r.get(0).trim().isEmpty()) gotWords = true;
        }
        public void onEvent(int t, Bundle b) { }
        public void onError(int e) {
            String id = curId; curId = null;
            if (id == null) return;
            // 6 SPEECH_TIMEOUT / 7 NO_MATCH = nobody spoke; anything else = the recogniser failed → let the stock assistant have it
            boolean quiet = e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || e == SpeechRecognizer.ERROR_NO_MATCH;
            log(id + " error " + e + " (max rms " + maxRms + " dB)");
            if (quiet) earcon("miss", new int[]{660, 440}, 110);     // nothing, but say we gave up
            if (e == SpeechRecognizer.ERROR_CLIENT || e == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) { rec.destroy(); rec = null; }
            write(id, "", quiet ? "no-speech" : "recogniser error " + e, (System.currentTimeMillis() - tPoke) / 1000.0, 0);
        }
        public void onResults(Bundle b) {
            String id = curId; curId = null;
            if (id == null) return;
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = r == null || r.isEmpty() ? "" : r.get(0);
            tFinal = System.currentTimeMillis();
            double listen = (tFinal - tPoke) / 1000.0;
            log(id + " heard " + text + " (" + listen + "s)");
            if (text.isEmpty()) { earcon("miss", new int[]{660, 440}, 110); write(id, "", "no-speech", listen, 0); return; }
            if (curNoDesk) { log(id + " no VPN — miss"); write(id, text, "desk unreachable (no VPN)", listen, 0); return; }
            boolean away = curAway;
            new Thread(() -> desk(id, text, listen, away)).start();     // network off the main thread
        }
    };

    static void desk(String id, String text, double listen, boolean away) {
        try {
            String tok = new String(Files.readAllBytes(Paths.get(AUTH_FILE)), StandardCharsets.UTF_8).trim();
            HttpURLConnection c = (HttpURLConnection) new URL(deskUrl()).openConnection();
            // desktop routing + reply synth; some commands wait on another device's answer, so allow up to 30 s
            c.setConnectTimeout(2500); c.setReadTimeout(30000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + tok);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] body = ("{\"text\": " + quote(text) + ", \"audio\": true, \"away\": " + away + "}").getBytes(StandardCharsets.UTF_8);
            try (OutputStream o = c.getOutputStream()) { o.write(body); }
            String resp;
            try (InputStream in = c.getResponseCode() == 200 ? c.getInputStream() : c.getErrorStream()) {
                resp = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            double d = (System.currentTimeMillis() - tFinal) / 1000.0;
            org.json.JSONObject o = c.getResponseCode() == 200 ? new org.json.JSONObject(resp) : new org.json.JSONObject();
            String wav = (String) o.remove("wav_b64");             // keep the audio out of the log and the result file
            boolean miss = c.getResponseCode() != 200 || o.optBoolean("miss", true);
            log(id + " desk " + c.getResponseCode() + " " + o + " (" + d + "s)");
            org.json.JSONObject out = new org.json.JSONObject().put("id", id).put("heard", text).put("miss", miss)
                    .put("secs_listen", listen).put("secs_desk", d).put("desk", o);
            Files.write(Paths.get(RUN, id + ".json.tmp"), out.toString().getBytes(StandardCharsets.UTF_8));
            new File(RUN, id + ".json.tmp").renameTo(new File(RUN, id + ".json"));   // on-wake.sh acts on this now
            if (miss) return;                                      // the stock assistant's job (on-wake.sh)
            if (wav != null) play(android.util.Base64.decode(wav, android.util.Base64.DEFAULT));
            else if (!"dup".equals(o.optString("what"))) earcon("sent", new int[]{880, 1320}, 90);   // chirp-only hit
            String pend = o.optString("pending", "");
            if (!pend.isEmpty()) pending(id, pend, tok, away);
        } catch (Exception e) {
            log(id + " desk unreachable: " + e);
            write(id, text, "desk unreachable", listen, (System.currentTimeMillis() - tFinal) / 1000.0);
        }
    }

    /** A desktop alert (alertd.sh, which already ran the dock gate). Never into an open mic: a wake in progress
     *  gets up to 15 s to finish first, or the recogniser would hear the alert as your words. */
    static void alert(String id, String path) {
        try {
            for (int i = 0; i < 150 && curId != null; i++) Thread.sleep(100);
            if (curId != null) log(id + " alert: mic still open after 15 s — playing anyway");
            byte[] w = Files.readAllBytes(Paths.get(path));
            new File(path).delete();
            log(id + " alert " + w.length + "B");
            Thread.sleep(earcon("sent", new int[]{880, 1320}, 90) + 80);
            play(w);
        } catch (Exception e) { log(id + " alert failed: " + e); }
    }

    /** A slow command: the desktop answered at once ("On it.") with a job id and runs the job in the background.
     *  Ask for the outcome (the desktop holds this request open until the job ends, up to ~80 s) and speak it.
     *  Holds its own wakelock: on-wake.sh's has been released by now. */
    static void pending(String id, String job, String tok, boolean away) {
        android.os.PowerManager.WakeLock wl = null;
        try {
            wl = ctx.getSystemService(android.os.PowerManager.class)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "stwake:pending");
            wl.acquire(100_000);
            log(id + " pending " + job);
            HttpURLConnection c = (HttpURLConnection) new URL(deskUrl() + "/pending").openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(90000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + tok);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) { out.write(("{\"id\": " + quote(job) + "}").getBytes(StandardCharsets.UTF_8)); }
            String resp;
            try (InputStream in = c.getResponseCode() == 200 ? c.getInputStream() : c.getErrorStream()) {
                resp = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            org.json.JSONObject o = c.getResponseCode() == 200 ? new org.json.JSONObject(resp) : new org.json.JSONObject();
            String wav = (String) o.remove("wav_b64");
            log(id + " pending " + c.getResponseCode() + " " + o);
            if (wav != null) play(android.util.Base64.decode(wav, android.util.Base64.DEFAULT));
            else earcon("miss", new int[]{660, 440}, 110);
            org.json.JSONArray said = o.optJSONArray("said");
            String last = said != null && said.length() > 0 ? said.optString(said.length() - 1, "").trim() : "";
            if (wav != null && last.endsWith("?")) followUp(id, away);
        } catch (Exception e) {
            log(id + " pending failed: " + e);
            earcon("miss", new int[]{660, 440}, 110);
        } finally { if (wl != null && wl.isHeld()) wl.release(); }
    }

    /** The background job asked you something: its reply from /pending ended in "?". Open the mic again (chirp, no
     *  wake word) and send the answer down the same desk() path; the desktop side keeps the conversation. At most
     *  FOLLOW_MAX rounds per wake ("f" per round in the id), then you say the wake word again. Only delayed (pending)
     *  replies do this, never a fixed "Say again?" line. */
    static void followUp(String id, boolean away) {
        int k = id.indexOf('~');
        int rounds = k < 0 ? 0 : id.length() - k - 1;      // "<wake id>~f", "~ff": one "f" per round ("~" is never in a wake id)
        if (rounds >= FOLLOW_MAX) { log(id + " follow-up: cap " + FOLLOW_MAX + " reached"); return; }
        String next = (k < 0 ? id + "~" : id) + "f";
        try {                                               // pending()'s wakelock goes when it returns: keep the CPU up
            ctx.getSystemService(android.os.PowerManager.class)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "stwake:followup").acquire(20_000);
        } catch (Throwable x) { log("followup wakelock: " + x); }
        log(id + " follow-up → " + next + (away ? " (away)" : ""));
        main.post(() -> poke(next + (away ? " away" : "")));
    }
    static final int FOLLOW_MAX = 2;

    /** Optional cue sounds: $D/earcons/{wake,miss,sent}.wav (16-bit PCM WAV, pushed by deploy.sh from ./earcons
     *  if that folder exists; none ship with this repo). Missing file → the sine-beep fallback below. */
    static int earcon(String name, int[] hz, int ms) {     // → how long it plays, ms
        try {
            byte[] w = Files.readAllBytes(Paths.get("/data/local/tmp/stwake/earcons", name + ".wav"));
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(w).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int bytesPerSec = b.getInt(28);
            new Thread(() -> play(w)).start();
            return bytesPerSec > 0 ? (int) ((w.length - 44) * 1000L / bytesPerSec) : ms;
        } catch (IOException e) { beep(hz, ms); return ms * hz.length; }
    }

    /** Cue tones on the SAME path as the reply (AudioTrack): the notification-stream ToneGenerator beep was
     *  far too quiet next to the spoken reply. One sine per frequency, ms each, 8 ms fades. */
    static void beep(int[] hz, int ms) {
        int sr = 24000, n = sr * ms / 1000, f = sr * 8 / 1000;
        byte[] w = new byte[44 + hz.length * n * 2];
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(w).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(w.length - 8).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
         .putShort((short) 1).putShort((short) 1).putInt(sr).putInt(sr * 2).putShort((short) 2).putShort((short) 16)
         .put("data".getBytes()).putInt(hz.length * n * 2);
        for (int h : hz)
            for (int i = 0; i < n; i++) {
                double env = Math.min(1, Math.min(i, n - 1 - i) / (double) f);
                b.putShort((short) (Math.sin(2 * Math.PI * h * i / sr) * 0.6 * 32767 * env));
            }
        new Thread(() -> play(w)).start();
    }

    static void buzz(int ms) {
        try { ctx.getSystemService(android.os.Vibrator.class).vibrate(android.os.VibrationEffect.createOneShot(ms, 180)); }
        catch (Throwable x) { log("buzz: " + x); }
    }

    /** The desktop's reply, rendered there in its own voice: 16-bit PCM WAV → AudioTrack, MEDIA usage, the
     *  volume rocker's stream. USAGE_ASSISTANT has its own volume group (AUDIO_STREAM_ASSISTANT) the rocker never
     *  touches: on it the cues stayed very loud even with the volume turned down. */
    static void play(byte[] w) {
        try {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(w).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int sr = b.getInt(24), ch = b.getShort(22), pos = 12, data = -1, len = 0;
            while (pos + 8 <= w.length) {                          // walk the chunks to "data"
                String id = new String(w, pos, 4, StandardCharsets.US_ASCII); int sz = b.getInt(pos + 4);
                if (id.equals("data")) { data = pos + 8; len = Math.min(sz, w.length - data); break; }
                pos += 8 + sz + (sz & 1);
            }
            if (data < 0) { log("play: no data chunk"); return; }
            android.media.AudioTrack t = new android.media.AudioTrack.Builder()
                    .setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new android.media.AudioFormat.Builder().setSampleRate(sr).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(ch == 2 ? android.media.AudioFormat.CHANNEL_OUT_STEREO : android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC).setBufferSizeInBytes(len).build();
            t.write(w, data, len);
            t.play();
            long ms = len * 1000L / (sr * 2L * ch);
            log("playing " + ms + "ms @" + sr);
            Thread.sleep(ms + 200);
            t.release();
        } catch (Throwable x) { log("play: " + x); }
    }

    /** A miss on our side (no speech, busy, recogniser or desktop failure). */
    static void write(String id, String heard, String why, double listen, double d) {
        try {
            Files.write(Paths.get(RUN, id + ".json.tmp"), ("{\"id\": " + quote(id) + ", \"heard\": " + quote(heard) + ", \"miss\": true"
                    + ", \"why\": " + quote(why) + ", \"secs_listen\": " + listen + ", \"secs_desk\": " + d + "}").getBytes(StandardCharsets.UTF_8));
            new File(RUN, id + ".json.tmp").renameTo(new File(RUN, id + ".json"));
        } catch (IOException e) { log("write " + id + ": " + e); }
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }
}
