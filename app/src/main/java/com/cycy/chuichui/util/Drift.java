package com.cycy.chuichui.util;

/** 圆盘内均匀采样的随机偏移（米 -> 度） */
public final class Drift {

    private static final double METERS_PER_DEG_LAT = 111320.0;

    private Drift() {
    }

    /**
     * @param lat    当前纬度
     * @param radius 半径（米），<=0 表示不漂移
     * @return {dLat, dLng}
     */
    public static double[] offset(double lat, double radius) {
        if (radius <= 0) return new double[]{0, 0};
        double r = radius * Math.sqrt(Math.random());
        double theta = Math.random() * Math.PI * 2;
        double dLat = (r * Math.cos(theta)) / METERS_PER_DEG_LAT;
        double cos = Math.cos(Math.toRadians(lat));
        if (Math.abs(cos) < 1e-6) cos = 1e-6;
        double dLng = (r * Math.sin(theta)) / (METERS_PER_DEG_LAT * cos);
        return new double[]{dLat, dLng};
    }

    /** 两点距离（米），用于日志 */
    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double dLat = (lat2 - lat1) * METERS_PER_DEG_LAT;
        double dLng = (lng2 - lng1) * METERS_PER_DEG_LAT * Math.cos(Math.toRadians(lat1));
        return Math.sqrt(dLat * dLat + dLng * dLng);
    }
}
