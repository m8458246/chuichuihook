package com.cycy.chuichui.model;

import org.json.JSONObject;

/**
 * 一份「伪造定位配置」。
 *
 * 这个类同时被模块 App（编辑/保存）和 hook 侧（目标进程内读取）使用，
 * 因此不能依赖任何 Context 或高德 SDK 类型 —— 纯数据 + JSON。
 *
 * 字段与高德 AMapLocation 的公开 setter 一一对应，hook 时按名字反射写入。
 */
public class Profile {

    public String id = "";
    public String name = "";
    public boolean enabled = true;

    /** 目标坐标（GCJ-02） */
    public double lat = 0;
    public double lng = 0;

    public double accuracy = 30.0;
    public double altitude = 0.0;
    public double bearing = 0.0;
    public double speed = 0.0;

    public String address = "";
    public String province = "";
    public String city = "";
    public String district = "";
    public String street = "";
    public String streetNumber = "";
    public String cityCode = "";
    public String adCode = "";
    public String country = "中国";
    public String poiName = "";
    public String aoiName = "";
    public String buildingId = "";
    public String floor = "";
    public String description = "";
    public String locationDetail = "";

    public String coordType = "GCJ02";
    public int locationType = 1;
    public int trustedLevel = 1;
    public int conScenario = 0;
    public int errorCode = 0;
    public String errorInfo = "";

    /** 每次定位回调的随机漂移半径（米），0 表示不漂移 */
    public double driftRadius = 5.0;

    public boolean wifiEnabled = true;
    public WifiSnapshot wifi = new WifiSnapshot();

    public boolean cellEnabled = true;
    public CellSnapshot cell = new CellSnapshot();

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("enabled", enabled);
            o.put("lat", lat);
            o.put("lng", lng);
            o.put("accuracy", accuracy);
            o.put("altitude", altitude);
            o.put("bearing", bearing);
            o.put("speed", speed);
            o.put("address", address);
            o.put("province", province);
            o.put("city", city);
            o.put("district", district);
            o.put("street", street);
            o.put("streetNumber", streetNumber);
            o.put("cityCode", cityCode);
            o.put("adCode", adCode);
            o.put("country", country);
            o.put("poiName", poiName);
            o.put("aoiName", aoiName);
            o.put("buildingId", buildingId);
            o.put("floor", floor);
            o.put("description", description);
            o.put("locationDetail", locationDetail);
            o.put("coordType", coordType);
            o.put("locationType", locationType);
            o.put("trustedLevel", trustedLevel);
            o.put("conScenario", conScenario);
            o.put("errorCode", errorCode);
            o.put("errorInfo", errorInfo);
            o.put("driftRadius", driftRadius);
            o.put("wifiEnabled", wifiEnabled);
            o.put("wifi", wifi.toJson());
            o.put("cellEnabled", cellEnabled);
            o.put("cell", cell.toJson());
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Profile fromJson(JSONObject o) {
        Profile p = new Profile();
        if (o == null) return p;
        p.id = o.optString("id", "");
        p.name = o.optString("name", "");
        p.enabled = o.optBoolean("enabled", true);
        p.lat = o.optDouble("lat", 0);
        p.lng = o.optDouble("lng", 0);
        p.accuracy = o.optDouble("accuracy", 30);
        p.altitude = o.optDouble("altitude", 0);
        p.bearing = o.optDouble("bearing", 0);
        p.speed = o.optDouble("speed", 0);
        p.address = o.optString("address", "");
        p.province = o.optString("province", "");
        p.city = o.optString("city", "");
        p.district = o.optString("district", "");
        p.street = o.optString("street", "");
        p.streetNumber = o.optString("streetNumber", "");
        p.cityCode = o.optString("cityCode", "");
        p.adCode = o.optString("adCode", "");
        p.country = o.optString("country", "中国");
        p.poiName = o.optString("poiName", "");
        p.aoiName = o.optString("aoiName", "");
        p.buildingId = o.optString("buildingId", "");
        p.floor = o.optString("floor", "");
        p.description = o.optString("description", "");
        p.locationDetail = o.optString("locationDetail", "");
        p.coordType = o.optString("coordType", "GCJ02");
        p.locationType = o.optInt("locationType", 1);
        p.trustedLevel = o.optInt("trustedLevel", 1);
        p.conScenario = o.optInt("conScenario", 0);
        p.errorCode = o.optInt("errorCode", 0);
        p.errorInfo = o.optString("errorInfo", "");
        p.driftRadius = o.optDouble("driftRadius", 5.0);
        p.wifiEnabled = o.optBoolean("wifiEnabled", true);
        p.wifi = WifiSnapshot.fromJson(o.optJSONObject("wifi"));
        p.cellEnabled = o.optBoolean("cellEnabled", true);
        p.cell = CellSnapshot.fromJson(o.optJSONObject("cell"));
        return p;
    }

    /** 用于缓存失效：配置内容变了就重算 */
    public String signature() {
        return lat + "|" + lng + "|" + driftRadius + "|" + enabled + "|" + address
                + "|" + province + "|" + city + "|" + district + "|" + street;
    }

    public String displayTitle() {
        if (name != null && !name.isEmpty()) return name;
        return String.format(java.util.Locale.US, "%.6f, %.6f", lat, lng);
    }

    public String displayAddress() {
        StringBuilder sb = new StringBuilder();
        append(sb, province);
        append(sb, city);
        append(sb, district);
        append(sb, street);
        append(sb, streetNumber);
        if (sb.length() == 0) append(sb, address);
        return sb.length() == 0 ? "（无地址）" : sb.toString();
    }

    private static void append(StringBuilder sb, String s) {
        if (s != null && !s.isEmpty() && !sb.toString().contains(s)) sb.append(s);
    }

    public Profile copy() {
        return fromJson(toJson());
    }
}
