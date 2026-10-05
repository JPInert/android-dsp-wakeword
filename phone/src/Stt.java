import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * PROBE: can a root app_process use Android's ON-DEVICE speech recognizer — the streaming,
 * self-endpointing one the stock assistants use (com.google.android.as) — with no app around it?
 * Context = ActivityThread.systemMain().getSystemContext() (reflection; there is no app).
 *
 * usage: Stt [support|listen] [timeoutSec 12]
 *   support → checkRecognitionSupport: installed / pending / supported languages, then exit
 *   listen  → startListening, print every partial + final with ms since start, exit on result/error/timeout
 */
public class Stt {
    static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS");
    static long t0;
    static void log(String s) { System.out.println(TS.format(new Date()) + " +" + (System.currentTimeMillis() - t0) + "ms " + s); System.out.flush(); }

    public static void main(String[] a) throws Throwable {
        t0 = System.currentTimeMillis();
        String mode = a.length > 0 ? a[0] : "support";
        int timeout = a.length > 1 ? Integer.parseInt(a[1]) : 12;
        android.os.Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context ctx = (Context) at.getMethod("getSystemContext").invoke(thread);
        log("context " + ctx.getPackageName() + " uid=" + android.os.Process.myUid());
        log("on-device available: " + SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
                + " recognitionAvailable=" + SpeechRecognizer.isRecognitionAvailable(ctx));
        SpeechRecognizer r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx);
        Intent in = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(() -> { log("TIMEOUT"); System.exit(4); }, timeout * 1000L);
        if (mode.equals("support")) {
            r.checkRecognitionSupport(in, Runnable::run, new RecognitionSupportCallback() {
                public void onSupportResult(RecognitionSupport s) {
                    log("installed=" + s.getInstalledOnDeviceLanguages() + " pending=" + s.getPendingOnDeviceLanguages()
                            + " supported=" + s.getSupportedOnDeviceLanguages().size() + " online=" + s.getOnlineLanguages().size());
                    System.exit(0);
                }
                public void onError(int e) { log("support error " + e); System.exit(3); }
            });
        } else {
            r.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle b) { log("ready"); }
                public void onBeginningOfSpeech() { log("speech begins"); }
                public void onRmsChanged(float v) { }
                public void onBufferReceived(byte[] b) { }
                public void onEndOfSpeech() { log("speech ends"); }
                public void onError(int e) { log("ERROR " + e); System.exit(2); }
                public void onResults(Bundle b) { log("FINAL " + b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)); System.exit(0); }
                public void onPartialResults(Bundle b) { log("partial " + b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)); }
                public void onEvent(int t, Bundle b) { log("event " + t); }
            });
            r.startListening(in);
            log("startListening sent");
        }
        android.os.Looper.loop();
    }
}
