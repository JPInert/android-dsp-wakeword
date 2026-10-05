import java.lang.reflect.*;

/** Root app_process probe: attach to the Qualcomm ST module as an originator, print the module, detach. */
public class Attach {
    public static void main(String[] a) throws Throwable {
        android.os.Looper.prepareMainLooper();
        Class<?> st = Class.forName("android.hardware.soundtrigger.SoundTrigger");
        Class<?> idC = Class.forName("android.media.permission.Identity");
        Class<?> lis = Class.forName("android.hardware.soundtrigger.SoundTrigger$StatusListener");
        Object id = idC.getConstructor().newInstance();
        idC.getField("uid").setInt(id, android.os.Process.myUid());
        idC.getField("pid").setInt(id, android.os.Process.myPid());
        idC.getField("packageName").set(id, a.length > 0 ? a[0] : "com.android.shell");
        Object l = Proxy.newProxyInstance(lis.getClassLoader(), new Class<?>[]{lis},
                (p, m, args) -> { System.out.println("cb " + m.getName() + " " + (args == null ? "" : java.util.Arrays.toString(args))); return null; });
        Method am = st.getMethod("attachModuleAsOriginator", int.class, lis, android.os.Handler.class, idC);
        Object mod = am.invoke(null, 0, l, new android.os.Handler(android.os.Looper.getMainLooper()), id);
        System.out.println("attached: " + mod);
        if (mod != null) mod.getClass().getMethod("detach").invoke(mod);
        System.out.println("detached");
    }
}
