import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * PROBE: does TextToSpeech bind from a uid-1000 app_process with the system context?
 * usage: Say "<text>"   — logs init/start/done with ms since main(), exits on done/error/8 s.
 * RESULT on my phone (Android 16): the process is SIGKILLed (rc 137) at the TextToSpeech constructor, as root or
 * uid 1000. Kept as a record of the dead end; `Say tone` (ToneGenerator + Vibrator) works.
 */
public class Say {
    static long t0;
    static void log(String s) { System.out.println(new SimpleDateFormat("HH:mm:ss.SSS").format(new Date()) + " +" + (System.currentTimeMillis() - t0) + "ms " + s); System.out.flush(); }

    public static void main(String[] a) throws Throwable {
        t0 = System.currentTimeMillis();
        android.os.Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Context ctx = (Context) at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
        log("context ok uid=" + android.os.Process.myUid());
        String text = a.length > 0 ? a[0] : "Phone voice test.";
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> { log("TIMEOUT"); System.exit(4); }, 8000);
        if (text.equals("tone")) {                    // probe: ToneGenerator + vibrate need no service binding
            android.media.ToneGenerator tg = new android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 60);
            boolean ok = tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120);
            log("tone " + ok);
            try {
                android.os.Vibrator v = ctx.getSystemService(android.os.Vibrator.class);
                v.vibrate(android.os.VibrationEffect.createOneShot(60, 180));
                log("vibrate hasVibrator=" + v.hasVibrator());
            } catch (Throwable t) { log("vibrate: " + t); }
            Thread.sleep(400); System.exit(0);
        }
        log("constructing TextToSpeech");
        final TextToSpeech[] tts = new TextToSpeech[1];
        tts[0] = new TextToSpeech(ctx, status -> {
            log("init status=" + status + " engine=" + tts[0].getDefaultEngine());
            if (status != TextToSpeech.SUCCESS) System.exit(2);
            tts[0].setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            tts[0].setOnUtteranceProgressListener(new UtteranceProgressListener() {
                public void onStart(String id) { log("start"); }
                public void onDone(String id) { log("done"); System.exit(0); }
                public void onError(String id) { log("error"); System.exit(3); }
            });
            tts[0].speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), "u1");
        });
        log("constructed");
        android.os.Looper.loop();
    }
}
