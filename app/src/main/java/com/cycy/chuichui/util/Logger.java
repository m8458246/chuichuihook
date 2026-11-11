package com.cycy.chuichui.util;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;

import com.cycy.chuichui.store.ConfigStore;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.api.XposedModule;

/**
 * hook 侧日志。
 *
 * 三个去处：
 *  1) logcat（tag=ChuiChui，LSPosed 会转发，前缀带模块名）
 *  2) 公共目录 /sdcard/Download/chuichuihook/hook.log —— 供人直接审计
 *  3) 模块 App 的日志页（广播回传，MediaStore 写不了时的兜底）
 *
 * 公共日志走 MediaStore：Android 11+ 不能直接 File 写 /sdcard，
 * 而往 Downloads 插自己创建的文件不需要任何存储权限。
 * 写操作丢到单独的 HandlerThread，不阻塞定位回调。
 */
public final class Logger {

    public static final String TAG = "ChuiChui";

    /** 公共日志目录（相对 Download） */
    public static final String LOG_DIR = Environment.DIRECTORY_DOWNLOADS + "/chuichuihook/";
    public static final String LOG_NAME = "hook.log";
    /** 最多保留行数，最新的在最上面 */
    private static final int MAX_LINES = 500;

    private static XposedModule module;
    private static volatile Context app;
    private static volatile boolean appResolved;
    private static volatile ExecutorService io;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private Logger() {
    }

    public static void attach(XposedModule m) {
        module = m;
    }

    /** 当前时间，和日志行里的时间戳格式一致 */
    public static String now() {
        return TS.format(new Date());
    }

    public static void i(String msg) {
        emit(Log.INFO, msg, null);
    }

    public static void w(String msg) {
        emit(Log.WARN, msg, null);
    }

    public static void e(String msg, Throwable t) {
        emit(Log.ERROR, msg, t);
    }

    /**
     * 一次定位替换的审计日志。
     * 格式：时间  实际坐标  目标坐标  偏移  字段数  地址
     */
    public static void location(double lat, double lng,
                                double targetLat, double targetLng,
                                double offsetMeters, int fields, String address) {
        String line = String.format(Locale.US,
                "%.6f,%.6f  目标 %.6f,%.6f  偏移 %.1fm  字段 %d%s",
                lat, lng, targetLat, targetLng, offsetMeters, fields,
                (address == null || address.isEmpty()) ? "" : ("  " + address));
        emit(Log.INFO, line, null);
    }

    // ------------------------------------------------------------------

    private static void emit(int priority, String msg, Throwable t) {
        if (module != null) {
            try {
                if (t != null) module.log(priority, TAG, msg, t);
                else module.log(priority, TAG, msg);
            } catch (Throwable ignored) {
                if (t != null) Log.println(priority, TAG, msg + ": " + t);
                else Log.println(priority, TAG, msg);
            }
        } else {
            if (t != null) Log.println(priority, TAG, msg + ": " + t);
            else Log.println(priority, TAG, msg);
        }
        final String line = TS.format(new Date()) + "  " + msg;
        append(line);
    }

    /** 追加到公共日志（最新在上），失败则广播回传给模块 App */
    private static void append(final String line) {
        final Context ctx = app();
        if (ctx == null) return;
        try {
            executor().execute(() -> {
                if (!appendToMediaStore(ctx, line)) {
                    relay(ctx, line);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static ExecutorService executor() {
        if (io == null) {
            synchronized (Logger.class) {
                if (io == null) io = Executors.newSingleThreadExecutor();
            }
        }
        return io;
    }

    /** 写 /sdcard/Download/chuichuihook/hook.log */
    private static boolean appendToMediaStore(Context ctx, String line) {
        if (Build.VERSION.SDK_INT < 29) return false;
        ContentResolver cr = ctx.getContentResolver();
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        try {
            Uri target = findLog(cr, collection);
            if (target == null) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, LOG_NAME);
                // 用 octet-stream 避免 MediaStore 自动补 .txt 后缀
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                cv.put(MediaStore.Downloads.RELATIVE_PATH, LOG_DIR);
                target = cr.insert(collection, cv);
            }
            if (target == null) return false;

            String old = readText(cr, target);
            String merged = line + "\n" + (old == null ? "" : old);
            String[] lines = merged.split("\n");
            if (lines.length > MAX_LINES) {
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < MAX_LINES; k++) sb.append(lines[k]).append('\n');
                merged = sb.toString();
            }
            try (OutputStream os = cr.openOutputStream(target, "wt")) {
                if (os == null) return false;
                os.write(merged.getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Uri findLog(ContentResolver cr, Uri collection) {
        Cursor c = null;
        try {
            // 用 LIKE：MediaStore 可能把 DISPLAY_NAME 补成 hook.log.txt 或 hook.log (1).txt
            c = cr.query(collection,
                    new String[]{MediaStore.Downloads._ID},
                    MediaStore.Downloads.DISPLAY_NAME + " LIKE ? AND "
                            + MediaStore.Downloads.RELATIVE_PATH + "=?",
                    new String[]{LOG_NAME + "%", LOG_DIR}, null);
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(collection, c.getLong(0));
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 读取公共日志（给模块 App 的日志页用） */
    public static String readPublicLog(Context ctx) {
        if (Build.VERSION.SDK_INT < 29) return "";
        ContentResolver cr = ctx.getContentResolver();
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        Uri target = findLog(cr, collection);
        if (target == null) return "";
        String text = readText(cr, target);
        return text == null ? "" : text;
    }

    private static String readText(ContentResolver cr, Uri uri) {
        InputStream in = null;
        try {
            in = cr.openInputStream(uri);
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** MediaStore 写不了时，把日志行广播回模块 App */
    private static void relay(Context ctx, String line) {
        try {
            Intent i = new Intent(ConfigStore.ACTION_LOG);
            i.setComponent(new android.content.ComponentName(
                    ConfigStore.MODULE_PACKAGE, ConfigStore.LOG_RECEIVER_CLASS));
            i.putExtra("line", line);
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            ctx.sendBroadcast(i);
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
        return app;
    }
}
