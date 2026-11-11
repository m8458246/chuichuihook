package com.cycy.chuichui.store;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
 * 主通道是显式广播：
 *  - 钉钉 targetSdk=30，受 Android 11+ 包可见性限制，ContentResolver.call 直接抛
 *    "Unknown authority"，所以 Provider 只能当兜底；
 *  - 显式广播（setPackage）不受包可见性限制，加 FLAG_INCLUDE_STOPPED_PACKAGES 后
 *    连被 force-stop 的模块 App 也能拉起；
 *  - 模块 App 保存配置时会主动推送一条广播，钉钉这边注册的动态 receiver 收到就更新缓存，
 *    配置变更即时生效。
 *
 * 另外还保留 openRemoteFile / getRemotePreferences 两条官方通道。
 */
public final class ConfigReader {

    private static final long TTL_MS = 3000;
    /** 推送过来的配置优先级更高、缓存更久：用户刚改完就该用新的 */
    private static final long PUSH_TTL_MS = 5 * 60 * 1000;

    private static XposedModule module;
    private static volatile Profiles cached;
    private static volatile long cachedAt;
    private static volatile String lastSource = "";
    private static volatile Context appContext;
    private static volatile boolean requesting;
    private static volatile boolean receiverRegistered;
    private static volatile boolean fromPush;
    private static boolean listedRemote;
    private static boolean loggedRemoteError;
    private static boolean loggedPrefs;

    private ConfigReader() {
    }

    public static void attach(XposedModule m) {
        module = m;
    }

    /** 由 Application.onCreate 的 hook 注入，最可靠的 Context 来源 */
    public static void setAppContext(Context ctx) {
        if (ctx == null) return;
        appContext = ctx;
        registerPushReceiver(ctx);
        requestAsync();
    }

    public static String lastSource() {
        return lastSource;
    }

    public static Profile current() {
        Profiles ps = cached;
        if (ps != null) {
            long age = SystemClock.elapsedRealtime() - cachedAt;
            long ttl = fromPush ? PUSH_TTL_MS : TTL_MS;
            if (age < ttl) return ps.currentProfile();
            fromPush = false;
        }
        ps = load();
        if (ps != null) {
            cached = ps;
            cachedAt = SystemClock.elapsedRealtime();
            return ps.currentProfile();
        }
        return null;
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
                    if (!loggedPrefs) {
                        loggedPrefs = true;
                        Logger.i("remote-prefs 条目数=" + sp.getAll().size()
                                + " jsonLen=" + (json == null ? -1 : json.length()));
                    }
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
            } catch (Throwable ignored) {
            }
        }

        // 4) 广播请求（异步，结果到了更新缓存）
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
            // 显式组件：包可见性只过滤隐式 intent，显式 ComponentName 不受限制。
            // 用 setPackage 的话，钉钉（targetSdk=30）看不到模块包，广播会被 AMS 直接丢弃。
            intent.setComponent(new android.content.ComponentName(
                    ConfigStore.MODULE_PACKAGE, ConfigStore.RECEIVER_CLASS));
            intent.setPackage(ConfigStore.MODULE_PACKAGE);
            // 模块 App 被 force-stop 过也要能收到
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        int code = getResultCode();
                        String json = getResultData();
                        if (valid(json)) {
                            Profiles ps = Profiles.fromJson(json);
                            cached = ps;
                            cachedAt = SystemClock.elapsedRealtime();
                            fromPush = true;
                            note("broadcast");
                            Logger.i("广播通道拿到配置，共 " + ps.list.size() + " 条");
                        } else {
                            Logger.w("广播返回空配置 code=" + code);
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

    /** 模块 App 保存配置时会推送，这里常驻接收，配置改完立即生效 */
    private static void registerPushReceiver(Context ctx) {
        if (receiverRegistered) return;
        receiverRegistered = true;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        String json = i == null ? null : i.getStringExtra("json");
                        if (valid(json)) {
                            Profiles ps = Profiles.fromJson(json);
                            cached = ps;
                            cachedAt = SystemClock.elapsedRealtime();
                            fromPush = true;
                            note("push");
                            Logger.i("收到配置推送，共 " + ps.list.size() + " 条");
                        }
                    } catch (Throwable t) {
                        Logger.w("处理配置推送失败: " + t);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ConfigStore.ACTION_PUSH);
            boolean ok = false;
            try {
                Method m = Context.class.getMethod("registerReceiver",
                        BroadcastReceiver.class, IntentFilter.class, int.class);
                // Context.RECEIVER_EXPORTED = 2
                m.invoke(ctx, receiver, filter, 2);
                ok = true;
            } catch (Throwable ignored) {
            }
            if (!ok) ctx.registerReceiver(receiver, filter);
            Logger.i("配置推送接收器已注册");
        } catch (Throwable t) {
            receiverRegistered = false;
            Logger.w("注册配置推送接收器失败: " + t);
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
