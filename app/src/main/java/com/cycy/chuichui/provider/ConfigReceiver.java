package com.cycy.chuichui.provider;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.cycy.chuichui.store.ConfigStore;

/**
 * 模块 App 侧的配置应答器。
 *
 * 目标进程（钉钉 targetSdk=30）受 Android 11+ 包可见性限制，
 * ContentResolver.call 会直接抛 "Unknown authority"，所以主通道改成显式广播：
 * 目标进程 sendOrderedBroadcast 过来，这里 setResultData 把 JSON 塞回去。
 *
 * 显式广播（setPackage）不受包可见性限制，静态注册的 receiver 在模块 App
 * 进程没运行时也会被系统拉起。
 */
public class ConfigReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (ConfigStore.ACTION_GET.equals(intent.getAction())) {
                setResultData(ConfigStore.load(context).toJson());
                setResultCode(android.app.Activity.RESULT_OK);
            }
        } catch (Throwable t) {
            setResultData("");
        }
    }
}
