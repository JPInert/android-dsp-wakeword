import java.lang.reflect.*;
import java.util.*;

/** Root app_process probe: can a shell-launched process reach the SoundTrigger middleware? Lists modules. */
public class Probe {
    public static void main(String[] a) throws Throwable {
        Class<?> st = Class.forName("android.hardware.soundtrigger.SoundTrigger");
        Class<?> idC = Class.forName("android.media.permission.Identity");
        Object id = idC.getConstructor().newInstance();
        idC.getField("uid").setInt(id, android.os.Process.myUid());
        idC.getField("pid").setInt(id, android.os.Process.myPid());
        idC.getField("packageName").set(id, a.length > 0 ? a[0] : "com.android.shell");
        for (Method m : st.getDeclaredMethods())
            if (m.getName().contains("Module")) System.out.println("M " + m);
        ArrayList<Object> mods = new ArrayList<>();
        Method lm = st.getMethod("listModulesAsOriginator", ArrayList.class, idC);
        Object rc = lm.invoke(null, mods, id);
        System.out.println("listModulesAsOriginator rc=" + rc + " n=" + mods.size());
        for (Object p : mods) System.out.println("  " + p);
    }
}
