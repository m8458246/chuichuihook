package com.cycy.chuichui.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 配置文件整体：一组 Profile + 当前选中的 id */
public class Profiles {

    public static final int VERSION = 1;

    public String current = "";
    public final List<Profile> list = new ArrayList<>();

    public Profile currentProfile() {
        if (list.isEmpty()) return null;
        for (Profile p : list) {
            if (p.id != null && p.id.equals(current)) return p;
        }
        return list.get(0);
    }

    public Profile find(String id) {
        for (Profile p : list) {
            if (p.id != null && p.id.equals(id)) return p;
        }
        return null;
    }

    public void remove(String id) {
        for (int i = 0; i < list.size(); i++) {
            if (id != null && id.equals(list.get(i).id)) {
                list.remove(i);
                break;
            }
        }
        if (id != null && id.equals(current)) {
            current = list.isEmpty() ? "" : list.get(0).id;
        }
    }

    public void put(Profile p) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id != null && list.get(i).id.equals(p.id)) {
                list.set(i, p);
                return;
            }
        }
        list.add(p);
    }

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("version", VERSION);
            o.put("current", current);
            JSONArray arr = new JSONArray();
            for (Profile p : list) arr.put(p.toJson());
            o.put("list", arr);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    public static Profiles fromJson(String json) {
        Profiles ps = new Profiles();
        if (json == null || json.trim().isEmpty()) return ps;
        try {
            JSONObject o = new JSONObject(json);
            ps.current = o.optString("current", "");
            JSONArray arr = o.optJSONArray("list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.optJSONObject(i);
                    if (item != null) ps.list.add(Profile.fromJson(item));
                }
            }
        } catch (Throwable ignored) {
        }
        return ps;
    }
}
