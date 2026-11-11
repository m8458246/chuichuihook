package com.cycy.chuichui.store;

import android.content.Context;
import android.content.SharedPreferences;

import com.cycy.chuichui.model.Profiles;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 模块 App 侧的配置存储。
 *
 * 同时写两个位置，对应 hook 侧的两条官方通道：
 *  1) filesDir/profiles.json      -> XposedInterface#openRemoteFile("profiles.json")
 *  2) SharedPreferences("profile") -> XposedInterface#getRemotePreferences("profile")
 * 第三条通道（ContentProvider）读的是同一份 SharedPreferences，无需额外写。
 */
public final class ConfigStore {

    public static final String FILE_NAME = "profiles.json";
    public static final String SP_NAME = "profile";
    public static final String SP_KEY = "json";
    public static final String AUTHORITY = "com.cycy.chuichui.config";

    /** 模块包名（目标进程里硬编码，用来发显式广播） */
    public static final String MODULE_PACKAGE = "com.cycy.chuichui";
    /** 目标进程请求配置的广播 action */
    public static final String ACTION_GET = "com.cycy.chuichui.GET_CONFIG";

    private ConfigStore() {
    }

    public static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    public static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    public static Profiles load(Context ctx) {
        // 1) files
        try {
            File f = file(ctx);
            if (f.exists()) {
                String json = readFile(f);
                if (json != null && !json.trim().isEmpty()) return Profiles.fromJson(json);
            }
        } catch (Throwable ignored) {
        }
        // 2) shared prefs
        try {
            String json = sp(ctx).getString(SP_KEY, null);
            if (json != null && !json.trim().isEmpty()) return Profiles.fromJson(json);
        } catch (Throwable ignored) {
        }
        return new Profiles();
    }

    public static void save(Context ctx, Profiles ps) {
        String json = ps.toJson();
        // files
        try {
            File f = file(ctx);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream out = new FileOutputStream(f);
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
        } catch (Throwable ignored) {
        }
        // shared prefs
        try {
            sp(ctx).edit().putString(SP_KEY, json).commit();
        } catch (Throwable ignored) {
        }
    }

    public static String readFile(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
