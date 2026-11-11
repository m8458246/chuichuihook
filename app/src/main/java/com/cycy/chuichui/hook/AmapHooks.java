package com.cycy.chuichui.hook;

import com.cycy.chuichui.model.Profile;
import com.cycy.chuichui.store.ConfigReader;
import com.cycy.chuichui.util.Drift;
import com.cycy.chuichui.util.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;

import io.github.libxposed.api.XposedModule;

/**
 * 高德定位 hook —— 本模块的核心。
 *
 * 完全用反射实现，不依赖高德 SDK 的任何类型：
 *  - 目标 App 里的类通过 Class.forName(name, false, cl) 拿；
 *  - 伪造对象用无参/字符串构造 + 公开 setter 反射写入；
 *  - 回调监听器用 java.lang.reflect.Proxy 包一层，不实现任何高德接口。
 *
 * 这样做的额外好处：模块 APK 不需要打包高德 SDK，也不会受 SDK 版本升级影响
 * （setter 找不到就跳过，不会崩）。
 */
public final class AmapHooks {

    private static final String CLS_CLIENT = "com.amap.api.location.AMapLocationClient";
    private static final String CLS_LOCATION = "com.amap.api.location.AMapLocation";
    private static final String CLS_LISTENER = "com.amap.api.location.AMapLocationListener";

    private AmapHooks() {
    }

    public static int install(XposedModule module, ClassLoader cl) {
        int n = 0;
        Class<?> clientCls;
        Class<?> listenerCls;
        Class<?> locationCls;
        try {
            clientCls = Class.forName(CLS_CLIENT, false, cl);
            listenerCls = Class.forName(CLS_LISTENER, false, cl);
            locationCls = Class.forName(CLS_LOCATION, false, cl);
        } catch (Throwable t) {
            Logger.w("高德定位类不存在，跳过定位 hook: " + t);
            return 0;
        }

        // 1) 直接返回伪造对象（钉钉读取"最后已知位置"时）
        try {
            Method m = clientCls.getDeclaredMethod("getLastKnownLocation");
            m.setAccessible(true);
            module.hook(m).setId("chuichui_last_known").intercept(chain -> {
                Profile p = ConfigReader.current();
                if (p == null || !p.enabled) return chain.proceed();
                Object fake = buildFake(locationCls, p, true);
                if (fake == null) return chain.proceed();
                logReplace(fake, p);
                return fake;
            });
            n++;
        } catch (Throwable t) {
            Logger.w("hook getLastKnownLocation 失败: " + t);
        }

        // 2) 包住定位回调，把伪造值刷到真实回调对象上
        //    高德有 setLocationListener(Listener) / setLocationListener(Listener, long) 等重载，
        //    这里按参数类型把所有重载都挂上，避免漏掉钉钉实际用的那个。
        int listenerHooks = 0;
        for (Method m : clientCls.getDeclaredMethods()) {
            if (!"setLocationListener".equals(m.getName())) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length == 0 || !pts[0].isAssignableFrom(listenerCls)) continue;
            try {
                m.setAccessible(true);
                module.hook(m).setId("chuichui_set_listener_" + pts.length).intercept(chain -> {
                    Object original = chain.getArg(0);
                    if (original == null) return chain.proceed();
                    Profile p = ConfigReader.current();
                    if (p == null || !p.enabled) return chain.proceed();
                    Object proxy = Proxy.newProxyInstance(cl, new Class[]{listenerCls},
                            new ListenerHandler(original, locationCls));
                    Object[] args = chain.getArgs().toArray();
                    args[0] = proxy;
                    return chain.proceed(args);
                });
                listenerHooks++;
            } catch (Throwable t) {
                Logger.w("hook setLocationListener(" + pts.length + " 参数) 失败: " + t);
            }
        }
        if (listenerHooks > 0) {
            n++;
        } else {
            Logger.w("没找到可用的 setLocationListener 重载");
        }

        // 自检：用当前配置构造一个伪造对象，再复制到一个空白对象上，
        // 验证「反射构造 + setter 写入 + 全字段复制」整条链路是否真的能跑通。
        // 这段不依赖任何真实定位回调，装完 hook 就能看到结果。
        try {
            Profile p = ConfigReader.current();
            if (p != null) {
                Object fake = buildFake(locationCls, p, true);
                Object real = newInstance(locationCls);
                if (fake != null && real != null) {
                    int fields = copyFields(fake, real);
                    Logger.i(String.format(Locale.US,
                            "自检通过: 复制字段 %d，坐标 %.6f,%.6f，地址 %s",
                            fields,
                            getDouble(real, "getLatitude", 0),
                            getDouble(real, "getLongitude", 0),
                            getString(real, "getAddress", "")));
                } else {
                    Logger.w("自检失败: 构造对象为空 fake=" + fake + " real=" + real);
                }
            }
        } catch (Throwable t) {
            Logger.e("自检异常", t);
        }

        return n;
    }

    // ------------------------------------------------------------------

    private static final class ListenerHandler implements InvocationHandler {

        private final Object target;
        private final Class<?> locationCls;

        ListenerHandler(Object target, Class<?> locationCls) {
            this.target = target;
            this.locationCls = locationCls;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("onLocationChanged".equals(method.getName()) && args != null && args.length == 1) {
                try {
                    handle(args[0]);
                } catch (Throwable t) {
                    Logger.w("定位替换失败: " + t);
                }
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private void handle(Object real) {
            if (real == null) return;
            Profile p = ConfigReader.current();
            if (p == null || !p.enabled) return;
            Object fake = buildFake(locationCls, p, true);
            if (fake == null) return;
            int fields = copyFields(fake, real);
            logReplace(fake, p, fields);
        }
    }

    // ------------------------------------------------------------------

    /** 按 Profile 构造一个伪造的 AMapLocation 实例 */
    static Object buildFake(Class<?> locationCls, Profile p, boolean drift) {
        Object fake = newInstance(locationCls);
        if (fake == null) return null;

        double lat = p.lat;
        double lng = p.lng;
        if (drift) {
            double[] d = Drift.offset(lat, p.driftRadius);
            lat += d[0];
            lng += d[1];
        }

        set(fake, "setLatitude", lat);
        set(fake, "setLongitude", lng);
        set(fake, "setAccuracy", p.accuracy);
        set(fake, "setAltitude", p.altitude);
        set(fake, "setBearing", p.bearing);
        set(fake, "setSpeed", p.speed);
        set(fake, "setAddress", p.address);
        set(fake, "setProvince", p.province);
        set(fake, "setCity", p.city);
        set(fake, "setDistrict", p.district);
        set(fake, "setStreet", p.street);
        set(fake, "setStreetNumber", p.streetNumber);
        set(fake, "setCityCode", p.cityCode);
        set(fake, "setAdCode", p.adCode);
        set(fake, "setCountry", p.country);
        set(fake, "setPoiName", p.poiName);
        set(fake, "setAoiName", p.aoiName);
        set(fake, "setBuildingId", p.buildingId);
        set(fake, "setFloor", p.floor);
        set(fake, "setDescription", p.description);
        set(fake, "setErrorCode", p.errorCode);
        set(fake, "setErrorInfo", p.errorInfo);
        set(fake, "setLocationType", p.locationType);
        set(fake, "setCoordType", p.coordType);
        set(fake, "setTrustedLevel", p.trustedLevel);
        set(fake, "setConScenario", p.conScenario);
        return fake;
    }

    /**
     * 把 src 的所有 getXxx 值写进 dst 的 setXxx。
     * dst 是真实回调对象（AMapLocation 实例），遍历它的 setter 而不是硬编码字段名，
     * 这样高德以后加字段也能自动覆盖。
     */
    static int copyFields(Object src, Object dst) {
        int n = 0;
        Method[] methods;
        try {
            methods = dst.getClass().getMethods();
        } catch (Throwable t) {
            return 0;
        }
        for (Method setter : methods) {
            String name = setter.getName();
            if (!name.startsWith("set") || name.length() <= 3 || setter.getParameterCount() != 1) continue;
            String suffix = name.substring(3);
            Object value;
            try {
                Method getter;
                try {
                    getter = src.getClass().getMethod("get" + suffix);
                } catch (NoSuchMethodException e) {
                    getter = src.getClass().getMethod("is" + suffix);
                }
                if (getter.getReturnType() != setter.getParameterTypes()[0]) continue;
                value = getter.invoke(src);
            } catch (Throwable e) {
                continue;
            }
            // null 也要覆盖（extras / locationDetail 这类对象字段留着真实值会泄露真实位置），
            // 基本类型参数传 null 会抛 IllegalArgumentException，被下面的 catch 吃掉
            try {
                setter.invoke(dst, value);
                n++;
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    static Object newInstance(Class<?> cls) {
        try {
            Constructor<?> c = cls.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Throwable ignored) {
        }
        try {
            Constructor<?> c = cls.getDeclaredConstructor(String.class);
            c.setAccessible(true);
            return c.newInstance("");
        } catch (Throwable ignored) {
        }
        try {
            Constructor<?> c = cls.getDeclaredConstructor(boolean.class);
            c.setAccessible(true);
            return c.newInstance(false);
        } catch (Throwable ignored) {
        }
        return null;
    }

    static void set(Object o, String name, Object v) {
        try {
            for (Method m : o.getClass().getMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
                Object arg = convert(m.getParameterTypes()[0], v);
                if (arg == null) continue;
                m.invoke(o, arg);
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    static Object convert(Class<?> pt, Object v) {
        if (v == null) return null;
        try {
            if (pt == double.class || pt == Double.class) return ((Number) v).doubleValue();
            if (pt == float.class || pt == Float.class) return ((Number) v).floatValue();
            if (pt == int.class || pt == Integer.class) return ((Number) v).intValue();
            if (pt == long.class || pt == Long.class) return ((Number) v).longValue();
            if (pt == short.class || pt == Short.class) return ((Number) v).shortValue();
            if (pt == byte.class || pt == Byte.class) return ((Number) v).byteValue();
            if (pt == boolean.class || pt == Boolean.class) return (Boolean) v;
            if (pt == String.class) return String.valueOf(v);
            if (pt.isInstance(v)) return v;
        } catch (Throwable ignored) {
        }
        return null;
    }

    static double getDouble(Object o, String name, double def) {
        try {
            Method m = o.getClass().getMethod(name);
            Object v = m.invoke(o);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignored) {
        }
        return def;
    }

    static String getString(Object o, String name, String def) {
        try {
            Method m = o.getClass().getMethod(name);
            Object v = m.invoke(o);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {
        }
        return def;
    }

    static void logReplace(Object fake, Profile p) {
        logReplace(fake, p, -1);
    }

    /** 每次定位替换都记一行到公共日志，带时间点、目标坐标和偏移量 */
    static void logReplace(Object fake, Profile p, int fields) {
        try {
            double lat = getDouble(fake, "getLatitude", p.lat);
            double lng = getDouble(fake, "getLongitude", p.lng);
            double dist = Drift.distance(p.lat, p.lng, lat, lng);
            String addr = getString(fake, "getAddress", "");
            Logger.location(lat, lng, p.lat, p.lng, dist, fields, addr);
        } catch (Throwable ignored) {
        }
    }
}
