package com.cycy.chuichui.provider;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.cycy.chuichui.store.ConfigStore;
import com.cycy.chuichui.util.HookLog;

/**
 * 日志兜底接收器。
 *
 * 目标进程优先把日志直接写进 /sdcard/Download/chuichuihook/hook.log（MediaStore），
 * 写不了的时候才会广播回这里，落到模块 App 的私有目录。
 */
public class LogReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (ConfigStore.ACTION_LOG.equals(intent.getAction())) {
                String line = intent.getStringExtra("line");
                if (line != null && !line.isEmpty()) HookLog.append(context, line);
            }
        } catch (Throwable ignored) {
        }
    }
}
