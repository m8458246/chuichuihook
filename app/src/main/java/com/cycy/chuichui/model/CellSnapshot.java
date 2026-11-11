package com.cycy.chuichui.model;

import android.os.Parcel;
import android.telephony.CellInfo;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 基站快照。CellInfo 的字段全是隐藏 API，重建代价大且易随系统版本变化，
 * 因此这里只保存原对象的 Parcel 字节（base64），hook 侧原样还原。
 */
public class CellSnapshot {

    public String operator = "";
    public int count = 0;
    public String summary = "";
    /** base64(Parcel(ArrayList<CellInfo>)) */
    public String parcel = "";

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("operator", operator);
            o.put("count", count);
            o.put("summary", summary);
            o.put("parcel", parcel);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static CellSnapshot fromJson(JSONObject o) {
        CellSnapshot c = new CellSnapshot();
        if (o == null) return c;
        c.operator = o.optString("operator", "");
        c.count = o.optInt("count", 0);
        c.summary = o.optString("summary", "");
        c.parcel = o.optString("parcel", "");
        return c;
    }

    public static String encode(List<CellInfo> list) {
        if (list == null) return "";
        Parcel p = Parcel.obtain();
        try {
            p.writeList(new ArrayList<CellInfo>(list));
            byte[] b = p.marshall();
            return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        } finally {
            p.recycle();
        }
    }

    public static List<CellInfo> restore(CellSnapshot s) {
        if (s == null || s.parcel == null || s.parcel.isEmpty()) return null;
        Parcel p = Parcel.obtain();
        try {
            byte[] b = android.util.Base64.decode(s.parcel, android.util.Base64.NO_WRAP);
            p.unmarshall(b, 0, b.length);
            p.setDataPosition(0);
            ArrayList<Object> list = p.readArrayList(CellInfo.class.getClassLoader());
            if (list == null) return null;
            List<CellInfo> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof CellInfo) out.add((CellInfo) o);
            }
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            p.recycle();
        }
    }

    public String summary() {
        if (count <= 0) return "未采集";
        return count + " 个基站";
    }
}
