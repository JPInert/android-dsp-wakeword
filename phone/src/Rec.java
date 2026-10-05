import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.io.*;
import java.nio.*;

/**
 * Record what follows the wake word: 16 kHz mono s16 WAV, from a root app_process, no app.
 * Stops on max length, on ~silenceMs of quiet after speech, or when no speech has started by noSpeechMs
 * (a "nobody said anything" miss). Speech = a 50 ms frame louder than max(3 x noise floor, 200 rms); the floor
 * is the quietest frame after the first 150 ms (MIC starts with digital zeros), never below 20.
 * Source: MIC. VOICE_RECOGNITION heard nothing once (n=1, DSP armed: peak 8 rms with a speaker talking;
 * MIC peak 752 and whisper read it back). Untested with the DSP off, so the cause is a guess.
 * Silence 1200 ms (900 cut a sentence at a comma).
 * Not used by the live path any more (Listen uses the on-device recogniser); kept as the record-a-WAV fallback.
 *
 * usage: Rec <out.wav> [maxMs 10000] [silenceMs 1200] [noSpeechMs 3000] [audioSource 1 = MIC]
 * prints one line: rec <reason> up0=<elapsedRealtime at first buffer> first=<ms main→first buffer> len=<ms> speech=<ms> floor=<rms> peak=<rms>
 */
public class Rec {
    public static void main(String[] a) throws Exception {
        long t0 = System.currentTimeMillis();
        String out = a[0];
        int maxMs = a.length > 1 ? Integer.parseInt(a[1]) : 10000;
        int silenceMs = a.length > 2 ? Integer.parseInt(a[2]) : 1200;
        int noSpeechMs = a.length > 3 ? Integer.parseInt(a[3]) : 3000;
        int src = a.length > 4 ? Integer.parseInt(a[4]) : MediaRecorder.AudioSource.MIC;
        final int RATE = 16000, FRAME = RATE / 20;               // 50 ms
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord(src, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 2 * 8));
        if (r.getState() != AudioRecord.STATE_INITIALIZED) { System.out.println("rec init-failed state=" + r.getState()); System.exit(2); }
        r.startRecording();
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(RATE * 2 * maxMs / 1000);
        short[] f = new short[FRAME];
        long first = -1, up0 = 0; int frames = 0, quiet = 0, speechFrames = 0;
        double floor = Double.MAX_VALUE, peak = 0;
        String why = "max";
        while (frames * 50 < maxMs) {
            int n = 0;
            while (n < FRAME) { int k = r.read(f, n, FRAME - n); if (k <= 0) { why = "read-error " + k; break; } n += k; }
            if (n < FRAME) break;
            if (first < 0) { first = System.currentTimeMillis() - t0; up0 = android.os.SystemClock.elapsedRealtime(); }
            frames++;
            double s = 0; for (short v : f) s += (double) v * v;
            double rms = Math.sqrt(s / FRAME);
            ByteBuffer b = ByteBuffer.allocate(FRAME * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (short v : f) b.putShort(v);
            pcm.write(b.array());
            if (frames > 3) floor = Math.max(20, Math.min(floor, rms));
            peak = Math.max(peak, rms);
            boolean loud = frames > 3 && rms > Math.max(3 * floor, 200);
            if (loud) { speechFrames++; quiet = 0; } else quiet++;
            if (speechFrames >= 3 && quiet * 50 >= silenceMs) { why = "silence"; break; }
            if (speechFrames < 3 && frames * 50 >= noSpeechMs) { why = "no-speech"; break; }
        }
        r.stop(); r.release();
        byte[] d = pcm.toByteArray();
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(out))) {
            ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes()).putInt(36 + d.length).put("WAVE".getBytes()).put("fmt ".getBytes())
             .putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2)
             .putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(d.length);
            o.write(h.array()); o.write(d);
        }
        System.out.println("rec " + why + " up0=" + up0 + " first=" + first + " len=" + frames * 50 + " speech=" + speechFrames * 50
                + " floor=" + (int) floor + " peak=" + (int) peak);
    }
}
