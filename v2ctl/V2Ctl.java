package v2ctl;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * 在 root 下（app_process）以 v2rayNG 自己的类构造 Serializable 参数，
 * 通过 Binder 直接启动它的内部服务，全程不需要把 App 显示到前台。
 *
 * 用法:
 *   app_process /system/bin v2ctl.V2Ctl <apk路径> update [--key=8] [--no-force] [subId...]
 *   app_process /system/bin v2ctl.V2Ctl <apk路径> test   [--key=7] <requestId>
 */
public final class V2Ctl {

    private static final String PKG = "com.v2ray.ang";

    public static void main(String[] args) {
        try {
            try {
                Class<?> vm = Class.forName("dalvik.system.VMRuntime");
                Object rt = vm.getMethod("getRuntime").invoke(null);
                vm.getMethod("setHiddenApiExemptions", String[].class)
                        .invoke(rt, (Object) new String[]{"L"});
            } catch (Throwable ignored) {
            }

            if (args.length < 1) {
                System.out.println("usage: V2Ctl <base.apk> update|test [options]");
                System.exit(2);
            }
            String apk = args[0];
            String what = args.length > 1 ? args[1] : "update";

            int key = what.equals("test") ? 7 : 8;
            boolean forced = true;
            boolean onlyTcp = false;
            ArrayList<String> extraIds = new ArrayList<String>();
            for (int i = 2; i < args.length; i++) {
                if (args[i].startsWith("--key=")) {
                    key = Integer.parseInt(args[i].substring(6));
                } else if (args[i].equals("--no-force")) {
                    forced = false;
                } else if (args[i].equals("--tcp")) {
                    onlyTcp = true;
                } else {
                    extraIds.add(args[i]);
                }
            }

            ClassLoader cl = (ClassLoader) Class.forName("dalvik.system.PathClassLoader")
                    .getConstructor(String.class, ClassLoader.class)
                    .newInstance(apk, ClassLoader.getSystemClassLoader());

            if ("select".equals(what)) {
                String guid = args.length > 2 ? args[2] : "";
                boolean ok = doSelect(cl, apk, guid);
                System.exit(ok ? 0 : 1);
            }

            String dtoName;
            String svcName;
            if (what.equals("test")) {
                dtoName = "com.v2ray.ang.dto.TestServiceMessage";
                svcName = "com.v2ray.ang.service.CoreTestService";
            } else {
                dtoName = "com.v2ray.ang.dto.SubscriptionUpdateMessage";
                svcName = "com.v2ray.ang.service.SubscriptionUpdateService";
            }

            Class<?> dto = cl.loadClass(dtoName);
            System.out.println("dto=" + dto + " loader=" + dto.getClassLoader());

            Object msg = what.equals("test")
                    ? newTestMessage(dto, key, extraIds, onlyTcp)
                    : newUpdateMessage(dto, key, forced, extraIds);
            System.out.println("msg=" + msg);

            Intent it = new Intent();
            it.setClassName(PKG, svcName);
            Bundle b = new Bundle();
            b.putSerializable("content", (Serializable) msg);
            it.putExtras(b);

            boolean ok = startViaActivityManager(it);
            if (!ok) {
                ok = startViaSystemContext(it);
            }
            System.out.println(ok ? "STARTED " + svcName + " key=" + key
                                 : "FAILED " + svcName + " key=" + key);
            System.exit(ok ? 0 : 1);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.exit(3);
        }
    }

    /** SubscriptionUpdateMessage(int key, boolean forcedUpdate, List<String> subIds) */
    private static Object newUpdateMessage(Class<?> dto, int key, boolean forced,
                                           ArrayList<String> ids) throws Exception {
        Constructor<?> c = dto.getConstructor(int.class, boolean.class, java.util.List.class);
        return c.newInstance(key, forced, ids);
    }

    /**
     * TestServiceMessage(int key, String subscriptionId, List<String> serverGuids, boolean onlyTcp)
     * 传入的第一个 id 当 subscriptionId，其余当 serverGuids；guids 为空表示该订阅下全部。
     */
    private static Object newTestMessage(Class<?> dto, int key, ArrayList<String> ids,
                                         boolean onlyTcp) throws Exception {
        String subId = ids.isEmpty() ? "" : ids.remove(0);
        Constructor<?> c = dto.getConstructor(int.class, String.class, java.util.List.class,
                boolean.class);
        return c.newInstance(key, subId, ids, onlyTcp);
    }

    @SuppressWarnings("unchecked")
    private static boolean startViaActivityManager(Intent it) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) sm.getMethod("getService", String.class)
                    .invoke(null, "activity");
            if (binder == null) {
                System.out.println("no activity binder");
                return false;
            }
            Class<?> stub = Class.forName("android.app.IActivityManager$Stub");
            Object am = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            Class<?> iam = Class.forName("android.app.IActivityManager");

            // 打印所有 start*Service* 签名，便于判断该 ROM 用的是哪一版
            for (Method m : iam.getMethods()) {
                String n = m.getName();
                if (n.startsWith("startService") || n.startsWith("startDelegate")) {
                    StringBuilder sb = new StringBuilder("  sig " + n + "(");
                    Class<?>[] p = m.getParameterTypes();
                    for (int i = 0; i < p.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(p[i].getSimpleName());
                    }
                    System.out.println(sb.append(")").toString());
                }
            }

            String[] names = {"startServiceInPackage", "startService"};
            for (String name : names) {
                for (Method m : iam.getMethods()) {
                    if (!name.equals(m.getName())) {
                        continue;
                    }
                    Class<?>[] p = m.getParameterTypes();
                    Object[] a = new Object[p.length];
                    int si = 0;
                    boolean fits = true;
                    for (int i = 0; i < p.length; i++) {
                        if (p[i] == int.class) {
                            a[i] = 0;
                        } else if (p[i] == boolean.class) {
                            // fgRequired=true -> 等价 startForegroundService，
                            // 绕过「应用在后台不允许启动服务」的限制；
                            // 两个服务都会在 onStartCommand 开头立刻 startForeground。
                            a[i] = Boolean.TRUE;
                        } else if (p[i] == Intent.class) {
                            a[i] = it;
                        } else if (p[i] == String.class) {
                            a[i] = (si++ == 0) ? null : PKG;
                        } else if (p[i].isInterface()) {
                            a[i] = null;
                        } else {
                            fits = false;
                            break;
                        }
                    }
                    if (!fits) {
                        continue;
                    }
                    try {
                        Object r = m.invoke(am, a);
                        System.out.println(name + "/" + a.length + " -> " + r);
                        return true;
                    } catch (Throwable t) {
                        Throwable c = (t instanceof java.lang.reflect.InvocationTargetException
                                && t.getCause() != null) ? t.getCause() : t;
                        System.out.println(name + "/" + a.length + " failed: " + c);
                    }
                }
            }
            System.out.println("no usable startService method");
        } catch (Throwable t) {
            System.out.println("AM path failed: " + t);
        }
        return false;
    }

    private static boolean startViaSystemContext(Intent it) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object th = at.getMethod("systemMain").invoke(null);
            Object ctx = at.getMethod("getSystemContext").invoke(th);
            Object r = ctx.getClass().getMethod("startService", Intent.class).invoke(ctx, it);
            System.out.println("ctx.startService -> " + r);
            return r != null;
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            System.out.println("CTX path failed: " + c);
        }
        return false;
    }

    /**
     * 把 SELECTED_SERVER 写成指定 guid —— 直接用它自己的 MMKV 实现，格式/CRC 由库维护。
     * MMKV 以 MULTI_PROCESS 模式（mode=2）打开，和 v2rayNG 内部一致。
     */
    private static boolean doSelect(ClassLoader cl, String apk, String guid) {
        try {
            if (guid == null || guid.length() != 32) {
                System.out.println("need 32-hex guid, got: " + guid);
                return false;
            }
            final String soPath = findMmkvSo(apk);
            if (soPath == null) {
                System.out.println("libmmkv.so not found near " + apk);
                return false;
            }
            System.out.println("mmkv so=" + soPath);

            Class<?> mmkvCls = cl.loadClass("com.tencent.mmkv.MMKV");
            Class<?> loaderItf = cl.loadClass("com.tencent.mmkv.MMKV$LibLoader");
            Class<?> levelCls = cl.loadClass("com.tencent.mmkv.MMKVLogLevel");
            Class<?> handlerCls = cl.loadClass("com.tencent.mmkv.MMKVHandler");

            Object libLoader = java.lang.reflect.Proxy.newProxyInstance(
                    cl, new Class<?>[]{loaderItf}, new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method m, Object[] a) {
                            if ("loadLibrary".equals(m.getName())) {
                                try {
                                    System.load(soPath);
                                } catch (Throwable t) {
                                    System.out.println("loadLibrary failed: " + t);
                                }
                            }
                            return null;
                        }
                    });

            Object levelInfo = null;
            for (Object o : levelCls.getEnumConstants()) {
                if ("LevelInfo".equals(((Enum<?>) o).name())) {
                    levelInfo = o;
                }
            }

            Method init = mmkvCls.getMethod("initialize", android.content.Context.class,
                    String.class, loaderItf, levelCls, handlerCls);
            Object root = init.invoke(null, new FakeCtx(),
                    "/data/data/" + PKG + "/files/mmkv", libLoader, levelInfo, null);
            System.out.println("mmkv root=" + root);

            Method withID = mmkvCls.getMethod("mmkvWithID", String.class, int.class);
            Object store = withID.invoke(null, "MAIN", 2);
            System.out.println("store=" + store);

            Method encode = mmkvCls.getMethod("encode", String.class, String.class);
            Object r = encode.invoke(store, "SELECTED_SERVER", guid);
            System.out.println("encode -> " + r);

            Method decode = mmkvCls.getMethod("decodeString", String.class, String.class);
            Object back = decode.invoke(store, "SELECTED_SERVER", null);
            System.out.println("readback=" + back);

            boolean ok = guid.equals(back);
            System.out.println(ok ? "SELECT_OK " + guid : "SELECT_FAIL");
            return ok;
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            System.out.println("select failed: " + c);
            c.printStackTrace(System.out);
            return false;
        }
    }

    private static String findMmkvSo(String apk) {
        try {
            java.io.File base = new java.io.File(apk).getParentFile();
            if (base == null) {
                return null;
            }
            java.io.File[] dirs = new java.io.File(base, "lib").listFiles();
            if (dirs == null) {
                return null;
            }
            for (java.io.File d : dirs) {
                java.io.File f = new java.io.File(d, "libmmkv.so");
                if (f.exists()) {
                    return f.getAbsolutePath();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 只覆盖 MMKV.initialize 会用到的三个方法，避免依赖真实 Application。 */
    public static final class FakeCtx extends android.content.ContextWrapper {
        public FakeCtx() {
            super(null);
        }

        @Override
        public android.content.pm.ApplicationInfo getApplicationInfo() {
            android.content.pm.ApplicationInfo ai = new android.content.pm.ApplicationInfo();
            ai.flags = 0;
            return ai;
        }

        @Override
        public java.io.File getCacheDir() {
            return new java.io.File("/data/data/" + PKG + "/cache");
        }

        @Override
        public java.io.File getFilesDir() {
            return new java.io.File("/data/data/" + PKG + "/files");
        }
    }
}
