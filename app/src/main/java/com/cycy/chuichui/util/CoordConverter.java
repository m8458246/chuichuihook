package com.cycy.chuichui.util;

/**
 * 坐标系转换：WGS84 ↔ GCJ-02（火星坐标）。
 *
 * 系统 LocationManager 返回的是 WGS84，高德/腾讯/百度系 SDK 用的是 GCJ-02。
 * 在中国境内两者差 300~600 米，直接混用会明显偏位。
 * 算法是公开的标准实现。
 */
public final class CoordConverter {

    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    private CoordConverter() {
    }

    public static boolean outOfChina(double lat, double lng) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    /** WGS84 → GCJ-02 */
    public static double[] wgs84ToGcj02(double lat, double lng) {
        if (outOfChina(lat, lng)) return new double[]{lat, lng};
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lng + dLng};
    }

    /** GCJ-02 → WGS84（迭代反解，误差在 1 米内） */
    public static double[] gcj02ToWgs84(double lat, double lng) {
        if (outOfChina(lat, lng)) return new double[]{lat, lng};
        double wgsLat = lat;
        double wgsLng = lng;
        for (int i = 0; i < 3; i++) {
            double[] gcj = wgs84ToGcj02(wgsLat, wgsLng);
            wgsLat += lat - gcj[0];
            wgsLng += lng - gcj[1];
        }
        return new double[]{wgsLat, wgsLng};
    }

    /** 两点距离（米） */
    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double dLat = (lat2 - lat1) * 111320.0;
        double dLng = (lng2 - lng1) * 111320.0 * Math.cos(Math.toRadians(lat1));
        return Math.sqrt(dLat * dLat + dLng * dLng);
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
}
