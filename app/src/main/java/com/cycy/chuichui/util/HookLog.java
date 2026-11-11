package com.cycy.chuichui.util;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 目标进程回传的运行日志，存在模块 App 私有目录。
 * 目标进程没有存储权限，所以只能通过 ContentProvider 把每行日志递回来。
 */
public final class HookLog {

    public static final String FILE_NAME = "hook.log";
    private static final int MAX_LINES = 300;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private HookLog() {
    }

    public static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    public static synchronized void append(Context ctx, String line) {
        try {
            List<String> lines = readLines(ctx);
            lines.add(TS.format(new Date()) + "  " + line);
            while (lines.size() > MAX_LINES) lines.remove(0);

            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');

            File f = file(ctx);
            FileOutputStream out = new FileOutputStream(f);
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void clear(Context ctx) {
        try {
            File f = file(ctx);
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized String read(Context ctx) {
        try {
            List<String> lines = readLines(ctx);
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static List<String> readLines(Context ctx) {
        List<String> lines = new ArrayList<>();
        try {
            File f = file(ctx);
            if (!f.exists()) return lines;
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            for (String l : text.split("\n")) {
                if (!l.isEmpty()) lines.add(l);
            }
        } catch (Throwable ignored) {
        }
        return lines;
    }
}
