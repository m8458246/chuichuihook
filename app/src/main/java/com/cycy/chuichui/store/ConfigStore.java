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
    /** 模块 App 主动推送配置的广播 action */
    public static final String ACTION_PUSH = "com.cycy.chuichui.PUSH_CONFIG";
    /** 目标应用包名（推送广播的接收方） */
    public static final String TARGET_PACKAGE = "com.alibaba.android.rimet";
    /** 配置应答接收器的完整类名（目标进程用显式 ComponentName 发广播） */
    public static final String RECEIVER_CLASS = "com.cycy.chuichui.provider.ConfigReceiver";

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
        // 发布到 LSPosed 的 shared data 目录（官方 openRemoteFile 通道读这里）
        final Context app = ctx.getApplicationContext();
        new Thread(() -> publishToLsposed(app), "chuichui-publish").start();
        // 主动推送给目标进程，配置改完立即生效（目标进程没运行就丢掉，下次启动会主动请求）
        push(ctx, json);
    }

    /**
     * 把 profiles.json 复制到 /data/adb/lspd/modules/<userId>/<pkg>/files/。
     *
     * 这个目录是 libxposed 的 "shared data directory"，目标进程通过
     * openRemoteFile 读取。目录本身在 /data/adb 下（root 700），普通 App
     * 写不了，所以这里借 su 复制。用户首次会看到一次 root 授权弹窗。
     *
     * @return 是否写入成功
     */
    public static boolean publishToLsposed(Context ctx) {
        try {
            File src = file(ctx);
            if (!src.exists()) return false;

            int userId = android.os.Process.myUid() / 100000;
            String dir = "/data/adb/lspd/modules/" + userId + "/" + MODULE_PACKAGE + "/files";
            String cmd = "mkdir -p '" + dir + "'"
                    + " && cp '" + src.getAbsolutePath() + "' '" + dir + "/" + FILE_NAME + "'"
                    + " && chmod 755 '" + dir + "'"
                    + " && chmod 666 '" + dir + "/" + FILE_NAME + "'";

            String[] suPaths = {"/system/bin/su", "/data/adb/ksu/bin/su", "/data/adb/magisk/su", "su"};
            for (String su : suPaths) {
                try {
                    File bin = new File(su);
                    if (!"su".equals(su) && !bin.exists()) continue;
                    Process p = Runtime.getRuntime().exec(new String[]{su, "-c", cmd});
                    int code = p.waitFor();
                    if (code == 0) return true;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void push(Context ctx, String json) {
        try {
            android.content.Intent i = new android.content.Intent(ACTION_PUSH);
            i.setPackage(TARGET_PACKAGE);
            i.putExtra("json", json);
            i.addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            ctx.sendBroadcast(i);
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
