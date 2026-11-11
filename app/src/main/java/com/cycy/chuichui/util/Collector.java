package com.cycy.chuichui.util;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.CellInfo;
import android.telephony.TelephonyManager;

import com.cycy.chuichui.model.CellSnapshot;
import com.cycy.chuichui.model.WifiSnapshot;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模块 App 侧的「采集真实环境」工具。
 *
 * 全部用系统 API，不引入高德 SDK —— 模块体积小、不需要 API key，
 * 且与 hook 侧完全解耦（hook 只用反射，不受这里影响）。
 */
public final class Collector {

    public interface LocateCallback {
        void onLocation(Location location, String error);
    }

    private Collector() {
    }

    /** 原生定位：先取最后已知位置，过期则请求一次更新，最多等 20 秒 */
    public static void locate(final Context ctx, final LocateCallback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        final LocationManager lm =
                (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            cb.onLocation(null, "设备没有定位服务");
            return;
        }

        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (Throwable ignored) {
        }

        if (best != null && System.currentTimeMillis() - best.getTime() < 5 * 60 * 1000L) {
            cb.onLocation(best, null);
            return;
        }

        final AtomicBoolean done = new AtomicBoolean(false);
        final LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (done.compareAndSet(false, true)) {
                    try {
                        lm.removeUpdates(this);
                    } catch (Throwable ignored) {
                    }
                    cb.onLocation(location, null);
                }
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };

        boolean requested = false;
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, listener, Looper.getMainLooper());
                requested = true;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0, listener, Looper.getMainLooper());
                requested = true;
            }
        } catch (Throwable ignored) {
        }

        if (!requested) {
            cb.onLocation(best, best == null ? "定位服务未开启（GPS/网络定位都关着）" : null);
            return;
        }

        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (done.compareAndSet(false, true)) {
                    try {
                        lm.removeUpdates(listener);
                    } catch (Throwable ignored) {
                    }
                    Location last = null;
                    try {
                        last = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                    } catch (Throwable ignored) {
                    }
                    cb.onLocation(last, last == null ? "20 秒内没有拿到定位（室内可到窗边或到室外重试）" : null);
                }
            }
        }, 20000);
    }

    /** 采集当前 WiFi 快照 */
    public static void fillWifi(Context ctx, WifiSnapshot w) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;

            w.wifiEnabled = wm.isWifiEnabled();

            WifiInfo info = wm.getConnectionInfo();
            if (info != null) {
                w.ssid = nullToEmpty(info.getSSID());
                w.bssid = nullToEmpty(info.getBSSID());
                w.mac = nullToEmpty(info.getMacAddress());
                w.rssi = info.getRssi();
                w.frequency = info.getFrequency();
                w.linkSpeed = info.getLinkSpeed();
                w.ipAddress = info.getIpAddress();
                w.networkId = info.getNetworkId();
                w.infoParcel = WifiSnapshot.encodeInfo(info);
            }

            List<ScanResult> scan = wm.getScanResults();
            if (scan != null) {
                w.scan.clear();
                for (ScanResult s : scan) {
                    WifiSnapshot.ScanItem it = new WifiSnapshot.ScanItem();
                    it.ssid = nullToEmpty(s.SSID);
                    it.bssid = nullToEmpty(s.BSSID);
                    it.level = s.level;
                    it.frequency = s.frequency;
                    w.scan.add(it);
                }
                w.scanParcel = WifiSnapshot.encodeScan(scan);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 采集当前基站快照 */
    public static void fillCell(Context ctx, CellSnapshot c) {
        try {
            TelephonyManager tm =
                    (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return;

            c.operator = nullToEmpty(tm.getNetworkOperator());
            List<CellInfo> list = tm.getAllCellInfo();
            if (list != null && !list.isEmpty()) {
                c.count = list.size();
                c.parcel = CellSnapshot.encode(list);
                c.summary = list.size() + " 个基站";
            } else {
                c.count = 0;
                c.parcel = "";
                c.summary = "系统未返回基站信息（可能需要位置权限或该机型限制）";
            }
        } catch (Throwable t) {
            c.summary = "采集失败: " + t;
        }
    }

    /** 逆地理编码（需要网络），失败返回 null */
    public static Address geocode(Context ctx, double lat, double lng) {
        try {
            Geocoder gc = new Geocoder(ctx, Locale.CHINA);
            List<Address> list = gc.getFromLocation(lat, lng, 1);
            if (list != null && !list.isEmpty()) return list.get(0);
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String addressText(Address a) {
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= a.getMaxAddressLineIndex(); i++) {
            String line = a.getAddressLine(i);
            if (line != null && !line.isEmpty()) sb.append(line);
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
