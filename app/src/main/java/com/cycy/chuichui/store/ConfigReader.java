package com.cycy.chuichui.store;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import com.cycy.chuichui.model.Profile;
import com.cycy.chuichui.model.Profiles;
import com.cycy.chuichui.util.Logger;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import io.github.libxposed.api.XposedModule;

/**
 * hook 侧（目标进程内）读取配置。
 *
 * 通道按可靠性排序：
 *  1) 显式广播（主通道）
 *     钉钉 targetSdk=30，受 Android 11+ 包可见性限制，ContentResolver.call 会抛
 *     "Unknown authority"。显式广播（setPackage）不受该限制，静态 receiver 还能
 *     把没在运行的模块 App 拉起来。
 *  2) openRemoteFile("profiles.json")  —— 官方文件通道
 *  3) getRemotePreferences("profile")  —— 官方 prefs 通道
 *  4) ContentProvider call("config")   —— 包可见性允许时可用
 *
 * 广播是异步的：Application.onCreate 时先发一次请求，定位回调来的时候结果通常已经到了。
 * 带 2 秒 TTL 缓存。
 */
public final class ConfigReader {

    private static final long TTL_MS = 2000;

    private static XposedModule module;
    private static volatile Profiles cached;
    private static volatile long cachedAt;
    private static volatile String lastSource = "";
    private static volatile Context appContext;
    private static volatile boolean requesting;
    private static boolean listedRemote;
    private static boolean loggedRemoteError;

    private ConfigReader() {
    }

    public static void attach(XposedModule m) {
        module = m;
    }

    /** 由 Application.onCreate 的 hook 注入，最可靠的 Context 来源 */
    public static void setAppContext(Context ctx) {
        if (ctx == null) return;
        appContext = ctx;
        requestAsync();
    }

    public static String lastSource() {
        return lastSource;
    }

    public static Profile current() {
        long now = SystemClock.elapsedRealtime();
        Profiles ps = cached;
        if (ps != null && now - cachedAt < TTL_MS) {
            return ps.currentProfile();
        }
        ps = load();
        if (ps != null) {
            cached = ps;
            cachedAt = now;
        }
        return cached == null ? null : cached.currentProfile();
    }

    private static Profiles load() {
        String json;

        // 1) openRemoteFile（模块 shared data 目录）
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
            json = readRemoteFile(ConfigStore.FILE_NAME);
            if (valid(json)) {
                note("remote-file");
                return Profiles.fromJson(json);
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

        // 3) ContentProvider（受包可见性限制，通常不可用，留着兜底）
        Context ctx = appContext;
        if (ctx != null) {
            try {
                Bundle b = ctx.getContentResolver().call(
                        Uri.parse("content://" + ConfigStore.AUTHORITY), "config", null, null);
                if (b != null) {
                    json = b.getString("json");
                    if (valid(json)) {
                        note("provider");
                        return Profiles.fromJson(json);
                    }
                }
            } catch (Throwable t) {
                Logger.w("provider 读取失败: " + t);
            }
        }

        // 4) 触发一次广播请求，结果到了会更新缓存
        requestAsync();
        note("none(等待广播)");
        return null;
    }

    /** 向模块 App 请求配置（显式有序广播，结果走 resultData） */
    private static void requestAsync() {
        Context ctx = appContext;
        if (ctx == null || requesting) return;
        requesting = true;
        try {
            Intent intent = new Intent(ConfigStore.ACTION_GET);
            intent.setPackage(ConfigStore.MODULE_PACKAGE);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        String json = getResultData();
                        if (valid(json)) {
                            Profiles ps = Profiles.fromJson(json);
                            cached = ps;
                            cachedAt = SystemClock.elapsedRealtime();
                            note("broadcast");
                            Logger.i("广播通道拿到配置，共 " + ps.list.size() + " 条");
                        } else {
                            Logger.w("广播返回空配置");
                        }
                    } catch (Throwable t) {
                        Logger.w("广播结果解析失败: " + t);
                    } finally {
                        requesting = false;
                    }
                }
            };
            ctx.sendOrderedBroadcast(intent, null, receiver,
                    new Handler(Looper.getMainLooper()), Activity.RESULT_OK, null, null);
        } catch (Throwable t) {
            requesting = false;
            Logger.w("请求配置广播失败: " + t);
        }
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

    /**
     * 只接受目标 App 自己的 Application Context。
     * 这里刻意不缓存失败结果：hook 安装时 Application 可能还没创建，
     * Application.onCreate hook 触发后就能拿到。
     */
    static Context appContext() {
        if (appContext != null) return appContext;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object app = m.invoke(null);
            if (app instanceof Context) {
                appContext = (Context) app;
                return appContext;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
