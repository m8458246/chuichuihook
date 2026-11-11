package com.cycy.chuichui.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.cycy.chuichui.R;
import com.cycy.chuichui.model.Profile;
import com.cycy.chuichui.model.Profiles;
import com.cycy.chuichui.store.ConfigStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private Profiles profiles;
    private ListView list;
    private ProfileAdapter adapter;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        list = findViewById(R.id.list);

        adapter = new ProfileAdapter();
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> select(position));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDelete(position);
            return true;
        });

        findViewById(R.id.btnNew).setOnClickListener(v ->
                startActivity(new Intent(this, EditActivity.class)));
        findViewById(R.id.btnSync).setOnClickListener(v -> sync());
        findViewById(R.id.btnLog).setOnClickListener(v ->
                startActivity(new Intent(this, LogActivity.class)));
    }

    /** 把配置同步到 LSPosed 的 shared data 目录（需要 root） */
    private void sync() {
        toast("正在写入 LSPosed 目录…（首次会请求 root 权限）");
        new Thread(() -> {
            final boolean ok = ConfigStore.publishToLsposed(getApplicationContext());
            runOnUiThread(() -> toast(ok
                    ? "已同步。钉钉下次定位就会读到新配置"
                    : "同步失败：需要给「锤锤102」授予 root 权限。\n"
                    + "也可以在 MT 管理器里手动复制：\n"
                    + "files/profiles.json → /data/adb/lspd/modules/0/com.cycy.chuichui/files/"));
        }, "chuichui-sync").start();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        profiles = ConfigStore.load(this);
        adapter.setData(profiles.list, profiles.current);
        updateStatus();
    }

    private void updateStatus() {
        Profile p = profiles == null ? null : profiles.currentProfile();
        if (p == null) {
            status.setText("当前没有可用配置。\n点下面「新建配置」，先采集一次当前位置，或手填经纬度。");
            return;
        }
        String text = String.format(Locale.US,
                "当前生效：%s\n坐标 %.6f, %.6f（漂移 ±%.0f 米）\n%s\n配置变更后钉钉下次定位自动生效（2 秒缓存）",
                p.displayTitle(), p.lat, p.lng, p.driftRadius, p.displayAddress());
        status.setText(text);
    }

    private void select(int position) {
        if (profiles == null || position < 0 || position >= profiles.list.size()) return;
        Profile p = profiles.list.get(position);
        profiles.current = p.id;
        ConfigStore.save(this, profiles);
        adapter.setData(profiles.list, profiles.current);
        updateStatus();
    }

    private void confirmDelete(int position) {
        if (profiles == null || position < 0 || position >= profiles.list.size()) return;
        final Profile p = profiles.list.get(position);
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除配置")
                .setMessage("确定删除「" + p.displayTitle() + "」？")
                .setPositiveButton("删除", (d, w) -> {
                    profiles.remove(p.id);
                    ConfigStore.save(MainActivity.this, profiles);
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------

    private class ProfileAdapter extends BaseAdapter {

        private final List<Profile> data = new ArrayList<>();
        private String currentId = "";

        void setData(List<Profile> list, String current) {
            data.clear();
            if (list != null) data.addAll(list);
            currentId = current == null ? "" : current;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_profile, parent, false);
            }
            Profile p = data.get(position);

            TextView check = v.findViewById(R.id.check);
            TextView title = v.findViewById(R.id.title);
            TextView coords = v.findViewById(R.id.coords);
            TextView address = v.findViewById(R.id.address);
            TextView extra = v.findViewById(R.id.extra);

            boolean active = p.id != null && p.id.equals(currentId);
            check.setText(active ? "●" : "○");
            check.setTextColor(active ? 0xFF1E88E5 : 0xFFBBBBBB);

            title.setText(p.displayTitle());
            coords.setText(String.format(Locale.US, "%.6f, %.6f   漂移 ±%.0fm",
                    p.lat, p.lng, p.driftRadius));
            address.setText(p.displayAddress());
            extra.setText("WiFi: " + p.wifi.summary() + "   基站: " + p.cell.summary()
                    + (p.enabled ? "" : "   [已停用]"));
            return v;
        }
    }
}
