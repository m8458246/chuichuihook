package com.cycy.chuichui.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

import com.cycy.chuichui.store.ConfigStore;
import com.cycy.chuichui.util.HookLog;

/**
 * 模块 App 与目标进程之间的兜底通道。
 *
 * 方法：
 *  - call("config")  -> Bundle("json" = profiles.json 内容)
 *  - call("log")     -> 目标进程把一行日志回传，落到 App 私有目录
 *  - query(...)      -> 同上，返回单行 json 的 cursor（老式调用方兼容）
 *
 * 注意：这里**故意不加 uid 校验**。原版锤锤的 provider 校验了 callingUid == myUid，
 * 导致目标进程调用必然抛 Unknown authority/IllegalState，回退通道形同虚设。
 * 本 provider 只暴露配置 JSON 和写日志，没有敏感数据。
 */
public class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = ConfigStore.AUTHORITY;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        try {
            if ("config".equals(method)) {
                out.putString("json", ConfigStore.load(getContext()).toJson());
            } else if ("log".equals(method)) {
                String line = extras == null ? arg : extras.getString("line");
                if (line != null) HookLog.append(getContext(), line);
            }
        } catch (Throwable t) {
            out.putString("error", String.valueOf(t));
        }
        return out;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{"json"});
        try {
            c.addRow(new Object[]{ConfigStore.load(getContext()).toJson()});
        } catch (Throwable t) {
            c.addRow(new Object[]{"{\"error\":\"" + t + "\"}"});
        }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "application/json";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
