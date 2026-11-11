package com.cycy.chuichui.util;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.cycy.chuichui.store.ConfigStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.libxposed.api.XposedModule;

/**
 * hook 侧日志：同时进 logcat（tag=ChuiChui）和模块 App 的日志页。
 *
 * 目标进程通常没有存储权限，写不了共享目录，所以日志通过 ContentProvider
 * 回传给模块 App（失败就只留 logcat，不影响目标进程）。
 */
public final class Logger {

    public static final String TAG = "ChuiChui";

    private static XposedModule module;
    private static Context app;
    private static boolean appResolved;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private Logger() {
    }

    public static void attach(XposedModule m) {
        module = m;
    }

    public static void i(String msg) {
        if (module != null) {
            try {
                module.log(Log.INFO, TAG, msg);
            } catch (Throwable t) {
                Log.i(TAG, msg);
            }
        } else {
            Log.i(TAG, msg);
        }
        relay(msg);
    }

    public static void w(String msg) {
        if (module != null) {
            try {
                module.log(Log.WARN, TAG, msg);
            } catch (Throwable t) {
                Log.w(TAG, msg);
            }
        } else {
            Log.w(TAG, msg);
        }
        relay(msg);
    }

    public static void e(String msg, Throwable t) {
        String line = msg + (t == null ? "" : (": " + t));
        if (module != null) {
            try {
                module.log(Log.ERROR, TAG, line, t);
            } catch (Throwable ignored) {
                Log.e(TAG, line, t);
            }
        } else {
            Log.e(TAG, line, t);
        }
        relay(line);
    }

    private static void relay(String msg) {
        try {
            Context ctx = app();
            if (ctx == null) return;
            Bundle b = new Bundle();
            b.putString("line", TS.format(new Date()) + "  " + msg);
            ctx.getContentResolver().call(
                    Uri.parse("content://" + ConfigStore.AUTHORITY), "log", null, b);
        } catch (Throwable ignored) {
        }
    }

    private static Context app() {
        if (app != null) return app;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object o = m.invoke(null);
            if (o instanceof Context) app = (Context) o;
        } catch (Throwable ignored) {
        }
        // 注意：不要用 getSystemContext 兜底 —— 它的 callingPackage 是 "android"，
        // 调 Provider 会被系统以 "calling package does not match uid" 拒绝
        return app;
    }
}
