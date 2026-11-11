package com.cycy.chuichui.hook;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;

import com.cycy.chuichui.model.Profile;
import com.cycy.chuichui.model.WifiSnapshot;
import com.cycy.chuichui.store.ConfigReader;
import com.cycy.chuichui.util.Logger;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * WiFi 相关 hook：伪造扫描列表、连接信息、开关状态。
 * 钉钉做网络定位时会读这些，跟坐标一起决定最终定位结果。
 */
public final class WifiHooks {

    private WifiHooks() {
    }

    public static int install(XposedModule module) {
        int n = 0;
        try {
            Class<?> cls = Class.forName("android.net.wifi.WifiManager");

            try {
                Method m = cls.getDeclaredMethod("isWifiEnabled");
                m.setAccessible(true);
                module.hook(m).setId("chuichui_wifi_enabled").intercept(chain -> {
                    Profile p = ConfigReader.current();
                    if (p == null || !p.wifiEnabled) return chain.proceed();
                    return p.wifi.wifiEnabled;
                });
                n++;
            } catch (Throwable t) {
                Logger.w("hook isWifiEnabled 失败: " + t);
            }

            try {
                Method m = cls.getDeclaredMethod("getConnectionInfo");
                m.setAccessible(true);
                module.hook(m).setId("chuichui_wifi_info").intercept(chain -> {
                    Profile p = ConfigReader.current();
                    if (p == null || !p.wifiEnabled) return chain.proceed();
                    Object info = WifiSnapshot.restoreInfo(p.wifi);
                    return info != null ? info : chain.proceed();
                });
                n++;
            } catch (Throwable t) {
                Logger.w("hook getConnectionInfo 失败: " + t);
            }

            try {
                Method m = cls.getDeclaredMethod("getScanResults");
                m.setAccessible(true);
                module.hook(m).setId("chuichui_wifi_scan").intercept(chain -> {
                    Profile p = ConfigReader.current();
                    if (p == null || !p.wifiEnabled) return chain.proceed();
                    List<ScanResult> list = WifiSnapshot.restoreScan(p.wifi);
                    return list != null ? list : chain.proceed();
                });
                n++;
            } catch (Throwable t) {
                Logger.w("hook getScanResults 失败: " + t);
            }
        } catch (Throwable t) {
            Logger.w("WiFi hook 安装失败: " + t);
        }
        return n;
    }
}
