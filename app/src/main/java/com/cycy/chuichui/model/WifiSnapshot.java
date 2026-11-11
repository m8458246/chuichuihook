package com.cycy.chuichui.model;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.os.Parcel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * WiFi 快照。
 *
 * 两套数据：
 *  - 可读字段（ssid/bssid/rssi...）用于界面展示与手工编辑；
 *  - infoParcel/scanParcel 是原对象的 Parcel 字节（base64），hook 侧直接反序列化返回，
 *    避免用隐藏 setter 重建 WifiInfo / ScanResult 带来的版本兼容问题。
 */
public class WifiSnapshot {

    public String ssid = "";
    public String bssid = "";
    public String mac = "";
    public int rssi = -50;
    public int frequency = 2437;
    public int linkSpeed = 72;
    public int ipAddress = 0;
    public int networkId = 0;
    public boolean wifiEnabled = true;

    public final List<ScanItem> scan = new ArrayList<>();

    /** base64(Parcel(WifiInfo)) */
    public String infoParcel = "";
    /** base64(Parcel(ArrayList<ScanResult>)) */
    public String scanParcel = "";

    public static class ScanItem {
        public String ssid = "";
        public String bssid = "";
        public int level = -50;
        public int frequency = 2437;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("ssid", ssid);
                o.put("bssid", bssid);
                o.put("level", level);
                o.put("frequency", frequency);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static ScanItem fromJson(JSONObject o) {
            ScanItem s = new ScanItem();
            if (o == null) return s;
            s.ssid = o.optString("ssid", "");
            s.bssid = o.optString("bssid", "");
            s.level = o.optInt("level", -50);
            s.frequency = o.optInt("frequency", 2437);
            return s;
        }
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("ssid", ssid);
            o.put("bssid", bssid);
            o.put("mac", mac);
            o.put("rssi", rssi);
            o.put("frequency", frequency);
            o.put("linkSpeed", linkSpeed);
            o.put("ipAddress", ipAddress);
            o.put("networkId", networkId);
            o.put("wifiEnabled", wifiEnabled);
            o.put("infoParcel", infoParcel);
            o.put("scanParcel", scanParcel);
            JSONArray arr = new JSONArray();
            for (ScanItem s : scan) arr.put(s.toJson());
            o.put("scan", arr);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static WifiSnapshot fromJson(JSONObject o) {
        WifiSnapshot w = new WifiSnapshot();
        if (o == null) return w;
        w.ssid = o.optString("ssid", "");
        w.bssid = o.optString("bssid", "");
        w.mac = o.optString("mac", "");
        w.rssi = o.optInt("rssi", -50);
        w.frequency = o.optInt("frequency", 2437);
        w.linkSpeed = o.optInt("linkSpeed", 72);
        w.ipAddress = o.optInt("ipAddress", 0);
        w.networkId = o.optInt("networkId", 0);
        w.wifiEnabled = o.optBoolean("wifiEnabled", true);
        w.infoParcel = o.optString("infoParcel", "");
        w.scanParcel = o.optString("scanParcel", "");
        JSONArray arr = o.optJSONArray("scan");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                w.scan.add(ScanItem.fromJson(arr.optJSONObject(i)));
            }
        }
        return w;
    }

    // ---------- Parcel 序列化（App 侧采集时调用） ----------

    public static String encodeInfo(WifiInfo info) {
        if (info == null) return "";
        Parcel p = Parcel.obtain();
        try {
            p.writeParcelable(info, 0);
            byte[] b = p.marshall();
            return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        } finally {
            p.recycle();
        }
    }

    public static String encodeScan(List<ScanResult> list) {
        if (list == null) return "";
        Parcel p = Parcel.obtain();
        try {
            p.writeList(new ArrayList<ScanResult>(list));
            byte[] b = p.marshall();
            return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        } finally {
            p.recycle();
        }
    }

    // ---------- Parcel 反序列化（hook 侧调用） ----------

    public static Object restoreInfo(WifiSnapshot s) {
        if (s == null || s.infoParcel == null || s.infoParcel.isEmpty()) return null;
        Parcel p = Parcel.obtain();
        try {
            byte[] b = android.util.Base64.decode(s.infoParcel, android.util.Base64.NO_WRAP);
            p.unmarshall(b, 0, b.length);
            p.setDataPosition(0);
            return p.readParcelable(WifiInfo.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        } finally {
            p.recycle();
        }
    }

    @SuppressWarnings("unchecked")
    public static List<ScanResult> restoreScan(WifiSnapshot s) {
        if (s == null || s.scanParcel == null || s.scanParcel.isEmpty()) return null;
        Parcel p = Parcel.obtain();
        try {
            byte[] b = android.util.Base64.decode(s.scanParcel, android.util.Base64.NO_WRAP);
            p.unmarshall(b, 0, b.length);
            p.setDataPosition(0);
            ArrayList<Object> list = p.readArrayList(ScanResult.class.getClassLoader());
            if (list == null) return null;
            List<ScanResult> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof ScanResult) out.add((ScanResult) o);
            }
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            p.recycle();
        }
    }

    public String summary() {
        if (ssid == null || ssid.isEmpty()) return "未采集";
        return ssid + "  (" + scan.size() + " 个热点)";
    }
}
