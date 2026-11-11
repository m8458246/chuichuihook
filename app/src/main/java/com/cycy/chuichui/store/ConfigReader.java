package com.cycy.chuichui.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import com.cycy.chuichui.model.Profile;
import com.cycy.chuichui.model.Profiles;
import com.cycy.chuichui.util.Logger;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * hook 侧（目标进程内）读取配置。
 *
 * 三条通道按可靠性排序，逐条尝试：
 *  1) openRemoteFile("profiles.json")      —— 官方文件通道，最直接
 *  2) getRemotePreferences("profile")      —— 官方 prefs 通道
 *  3) ContentProvider call("config")       —— 本地 Binder，无权限门槛
 *
 * 带 2 秒 TTL 缓存，避免每次定位回调都读一遍。
 */
public final class ConfigReader {

    private static final long TTL_MS = 2000;

    private static XposedModule module;
    private static Profiles cached;
    private static long cachedAt;
    private static String lastSource = "";
    private static Context appContext;
    private static boolean listedRemote;
    private static boolean loggedRemoteError;

    private ConfigReader() {
    }

    public static void attach(XposedModule m) {
        module = m;
    }

    public static String lastSource() {
        return lastSource;
    }

    public static Profile current() {
        long now = SystemClock.elapsedRealtime();
        if (cached != null && now - cachedAt < TTL_MS) {
            return cached.currentProfile();
        }
        Profiles ps = load();
        if (ps != null) {
            cached = ps;
            cachedAt = now;
        }
        return cached == null ? null : cached.currentProfile();
    }

    private static Profiles load() {
        String json;

        // 1) openRemoteFile（先列一次目录，方便定位路径语义）
        if (module != null) {
            if (!listedRemote) {
                listedRemote = true;
                try {
                    String[] files = module.listRemoteFiles();
                    StringBuilder sb = new StringBuilder();
                    if (files != null) {
                        for (String f : files) sb.append(f).append(' ');
                    }
                    Logger.i("remote files: [" + sb.toString().trim() + "]");
                } catch (Throwable t) {
                    Logger.w("listRemoteFiles 失败: " + t);
                }
            }
            for (String name : new String[]{ConfigStore.FILE_NAME, "files/" + ConfigStore.FILE_NAME}) {
                json = readRemoteFile(name);
                if (valid(json)) {
                    note("remote-file(" + name + ")");
                    return Profiles.fromJson(json);
                }
            }
        }

        // 2) remote preferences
        if (module != null) {
            try {
                SharedPreferences sp = module.getRemotePreferences(ConfigStore.SP_NAME);
                if (sp != null) {
                    json = sp.getString(ConfigStore.SP_KEY, null);
                    if (valid(json)) {
                        note("remote-prefs");
                        return Profiles.fromJson(json);
                    }
                }
            } catch (Throwable t) {
                Logger.w("remote-prefs 读取失败: " + t);
            }
        }

        // 3) ContentProvider
        try {
            Context ctx = appContext();
            if (ctx == null) {
                Logger.w("拿不到 Context，provider 通道跳过");
            } else {
                Bundle b = ctx.getContentResolver().call(
                        Uri.parse("content://" + ConfigStore.AUTHORITY), "config", null, null);
                if (b != null) {
                    json = b.getString("json");
                    if (valid(json)) {
                        note("provider");
                        return Profiles.fromJson(json);
                    }
                }
            }
        } catch (Throwable t) {
            Logger.w("provider 读取失败: " + t);
        }

        note("none");
        return null;
    }

    private static boolean valid(String json) {
        return json != null && !json.trim().isEmpty() && json.contains("\"list\"");
    }

    private static void note(String source) {
        if (!source.equals(lastSource)) {
            lastSource = source;
            Logger.i("配置来源: " + source);
        }
    }

    private static String readRemoteFile(String name) {
        ParcelFileDescriptor pfd = null;
        FileInputStream in = null;
        try {
            pfd = module.openRemoteFile(name);
            if (pfd == null) return null;
            in = new FileInputStream(pfd.getFileDescriptor());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            if (!loggedRemoteError) {
                loggedRemoteError = true;
                Logger.w("openRemoteFile(" + name + ") 失败: " + t);
            }
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            try {
                if (pfd != null) pfd.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 目标进程里没有 Context：先试 currentApplication，再退到 systemContext */
    static Context appContext() {
        if (appContext != null) return appContext;

        // 1) ActivityThread.currentApplication()（App 已经起来时最准）
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object app = m.invoke(null);
            if (app instanceof Context) {
                appContext = (Context) app;
                return appContext;
            }
        } catch (Throwable t) {
            Logger.w("currentApplication 不可用: " + t);
        }

        // 2) ActivityThread.currentActivityThread().getSystemContext()
        //    系统 Context 的 ContentResolver 调 Provider 不受调用方包可见性影响
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cur = at.getDeclaredMethod("currentActivityThread");
            cur.setAccessible(true);
            Object thread = cur.invoke(null);
            if (thread != null) {
                Method gsc = at.getDeclaredMethod("getSystemContext");
                gsc.setAccessible(true);
                Object ctx = gsc.invoke(thread);
                if (ctx instanceof Context) {
                    appContext = (Context) ctx;
                    Logger.i("使用 systemContext 访问 provider");
                    return appContext;
                }
            }
        } catch (Throwable t) {
            Logger.w("systemContext 不可用: " + t);
        }

        return null;
    }
}
