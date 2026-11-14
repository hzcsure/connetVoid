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
            ArrayList<String> extraIds = new ArrayList<String>();
            for (int i = 2; i < args.length; i++) {
                if (args[i].startsWith("--key=")) {
                    key = Integer.parseInt(args[i].substring(6));
                } else if (args[i].equals("--no-force")) {
                    forced = false;
                } else {
                    extraIds.add(args[i]);
                }
            }

            ClassLoader cl = (ClassLoader) Class.forName("dalvik.system.PathClassLoader")
                    .getConstructor(String.class, ClassLoader.class)
                    .newInstance(apk, ClassLoader.getSystemClassLoader());

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

            Object msg = newInstance(dto, key, forced, extraIds);
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

    /** 优先用 (int,boolean,List)；TestServiceMessage 之类退化到按参数个数匹配。 */
    private static Object newInstance(Class<?> dto, int key, boolean forced, ArrayList<String> ids)
            throws Exception {
        try {
            Constructor<?> c = dto.getConstructor(int.class, boolean.class, java.util.List.class);
            return c.newInstance(key, forced, ids);
        } catch (NoSuchMethodException ignored) {
        }
        for (Constructor<?> c : dto.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            Object[] a = new Object[p.length];
            int si = 0;
            boolean fits = true;
            for (int i = 0; i < p.length; i++) {
                if (p[i] == int.class) {
                    a[i] = key;
                } else if (p[i] == boolean.class) {
                    a[i] = forced;
                } else if (p[i] == String.class) {
                    a[i] = si < ids.size() ? ids.get(si) : "";
                    si++;
                } else if (java.util.List.class.isAssignableFrom(p[i])) {
                    a[i] = ids;
                } else {
                    fits = false;
                    break;
                }
            }
            if (fits) {
                return c.newInstance(a);
            }
        }
        throw new NoSuchMethodException("no usable constructor on " + dto);
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
                            a[i] = Boolean.FALSE;
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
}
