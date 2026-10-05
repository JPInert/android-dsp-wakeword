import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Turn a QVA first-stage .uim into the bytes the Qualcomm ST HAL wants, using QVA's OWN codec:
 * QVA's apk must be on CLASSPATH (for com.quicinc...SoundModelCodec/BigSoundModel) and its native
 * libs in LIB_DIR. Same order QVA uses: query → getByteBufferSize → createFirstStageSM.
 * Neither the apk nor the libs ship with this repo: copy them from your own phone (see the README).
 */
public class Codec {
    static final String LIB_DIR = "/data/local/tmp/stwake/lib";

    static void loadLibs() {
        // dependency order; libqvajni last (its JNI_OnLoad binds QVA's classes on the shared classpath)
        for (String l : new String[]{"libqvasml.so", "libqvapdk.so", "libqvapdkxs.so", "libqvauv.so", "libqvajni.so"}) {
            try { System.load(LIB_DIR + "/" + l); System.out.println("loaded " + l); }
            catch (Throwable t) { System.out.println("load " + l + " FAILED: " + t); }
        }
    }

    static byte[] firstStage(String uim) throws Throwable {
        byte[] raw = Files.readAllBytes(Paths.get(uim));
        ByteBuffer in = ByteBuffer.allocateDirect(raw.length);
        in.put(raw); in.rewind();
        Class<?> big = Class.forName("com.quicinc.voice.activation.soundmodel.BigSoundModel");
        Class<?> codec = Class.forName("com.quicinc.voice.activation.soundmodel.SoundModelCodec");
        Object bsm = big.getConstructor().newInstance();
        int q = (int) codec.getMethod("query", ByteBuffer.class, big).invoke(null, in, bsm);
        System.out.println("query rc=" + q);
        List<?> sms = (List<?>) big.getMethod("getSoundModelList").invoke(bsm);
        for (Object sm : sms) {
            Object cid = sm.getClass().getMethod("getConfigurationId").invoke(sm);
            byte[] d = (byte[]) sm.getClass().getMethod("getData").invoke(sm);
            System.out.println("  model " + cid + " data=" + (d == null ? "null" : d.length + "B"));
        }
        int size = (int) codec.getMethod("getByteBufferSize", big).invoke(null, bsm);
        System.out.println("getByteBufferSize: " + size);
        ByteBuffer out = ByteBuffer.allocateDirect(size);
        int c = (int) codec.getMethod("createFirstStageSM", big, ByteBuffer.class).invoke(null, bsm, out);
        System.out.println("createFirstStageSM rc=" + c);
        byte[] b = new byte[size];
        out.rewind(); out.get(b);
        return b;
    }

    public static void main(String[] a) throws Throwable {
        loadLibs();
        byte[] b = firstStage(a[0]);
        Files.write(Paths.get(a[1]), b);
        System.out.println("wrote " + b.length + "B to " + a[1]);
    }
}
