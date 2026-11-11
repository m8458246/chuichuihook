package com.cycy.chuichui.ui;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Location;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.cycy.chuichui.R;
import com.cycy.chuichui.model.Profile;
import com.cycy.chuichui.model.Profiles;
import com.cycy.chuichui.store.ConfigStore;
import com.cycy.chuichui.util.Collector;
import com.cycy.chuichui.util.CoordConverter;

import java.util.Locale;

public class EditActivity extends Activity {

    private static final int REQ_PERM = 1001;

    private Profiles profiles;
    private Profile profile;

    private EditText etName;
    private EditText etLat;
    private EditText etLng;
    private EditText etRadius;

    private EditText etProvince;
    private EditText etCity;
    private EditText etDistrict;
    private EditText etStreet;
    private EditText etStreetNumber;
    private EditText etAddress;
    private EditText etPoi;
    private EditText etAoi;

    private EditText etCityCode;
    private EditText etAdCode;
    private EditText etCountry;

    private EditText etAccuracy;
    private EditText etAltitude;
    private EditText etLocationType;
    private EditText etCoordType;
    private EditText etTrustedLevel;
    private EditText etConScenario;

    private Switch swWifi;
    private Switch swCell;
    private TextView tvWifi;
    private TextView tvCell;
    private TextView tvHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);

        profiles = ConfigStore.load(this);

        String id = getIntent().getStringExtra("id");
        Profile found = id == null ? null : profiles.find(id);
        if (found != null) {
            profile = found;
        } else {
            profile = new Profile();
            profile.id = "p" + System.currentTimeMillis();
        }

        buildForm();

        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());

        requestPerms();
    }

    private void requestPerms() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.READ_PHONE_STATE
            }, REQ_PERM);
        }
    }

    // ------------------------------------------------------------------

    private void buildForm() {
        LinearLayout c = findViewById(R.id.container);

        section(c, "基本信息");
        etName = field(c, "名称（例如：公司、家）", profile.name, InputType.TYPE_CLASS_TEXT);

        section(c, "坐标（GCJ-02，高德坐标系）");
        etLat = field(c, "纬度", fmt(profile.lat), InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        etLng = field(c, "经度", fmt(profile.lng), InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        etRadius = field(c, "漂移半径（米，0 = 不漂移，默认 5）",
                fmt(profile.driftRadius), InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        Button btnCollect = button(c, getString(R.string.btn_collect));
        btnCollect.setOnClickListener(v -> collect());

        Button btnGeo = button(c, getString(R.string.btn_geocode));
        btnGeo.setOnClickListener(v -> geocodeCurrent());

        tvHint = new TextView(this);
        tvHint.setTextSize(12);
        tvHint.setTextColor(0xFF666666);
        tvHint.setPadding(0, dp(6), 0, dp(6));
        tvHint.setText("采集用的是系统定位（不是高德 SDK），拿到后会自动从 WGS84 转成 GCJ-02；室内拿不到就直接手填。");
        c.addView(tvHint);

        section(c, "地址（会原样写进 AMapLocation）");
        etProvince = field(c, "省", profile.province, InputType.TYPE_CLASS_TEXT);
        etCity = field(c, "市", profile.city, InputType.TYPE_CLASS_TEXT);
        etDistrict = field(c, "区/县", profile.district, InputType.TYPE_CLASS_TEXT);
        etStreet = field(c, "街道", profile.street, InputType.TYPE_CLASS_TEXT);
        etStreetNumber = field(c, "门牌号", profile.streetNumber, InputType.TYPE_CLASS_TEXT);
        etAddress = field(c, "完整地址（留空则用上面拼）", profile.address, InputType.TYPE_CLASS_TEXT);
        etPoi = field(c, "POI 名称", profile.poiName, InputType.TYPE_CLASS_TEXT);
        etAoi = field(c, "AOI 名称", profile.aoiName, InputType.TYPE_CLASS_TEXT);

        section(c, "编码 / 国家");
        etCityCode = field(c, "城市区号 cityCode", profile.cityCode, InputType.TYPE_CLASS_TEXT);
        etAdCode = field(c, "行政区划码 adCode", profile.adCode, InputType.TYPE_CLASS_TEXT);
        etCountry = field(c, "国家", profile.country, InputType.TYPE_CLASS_TEXT);

        section(c, "高级（不懂就保持默认）");
        etAccuracy = field(c, "精度 accuracy（米）", fmt(profile.accuracy),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etAltitude = field(c, "海拔 altitude（米）", fmt(profile.altitude),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etLocationType = field(c, "定位类型 locationType（1=GPS 等）",
                String.valueOf(profile.locationType), InputType.TYPE_CLASS_NUMBER);
        etCoordType = field(c, "坐标系 coordType（GCJ02 / WGS84）",
                profile.coordType, InputType.TYPE_CLASS_TEXT);
        etTrustedLevel = field(c, "可信度 trustedLevel",
                String.valueOf(profile.trustedLevel), InputType.TYPE_CLASS_NUMBER);
        etConScenario = field(c, "场景 conScenario",
                String.valueOf(profile.conScenario), InputType.TYPE_CLASS_NUMBER);

        section(c, "WiFi 伪造");
        swWifi = switchRow(c, "启用 WiFi 伪造（扫描列表 / 连接信息 / 开关状态）", profile.wifiEnabled);
        tvWifi = note(c, "当前快照：" + profile.wifi.summary());
        Button btnWifi = button(c, getString(R.string.btn_collect_wifi));
        btnWifi.setOnClickListener(v -> {
            Collector.fillWifi(this, profile.wifi);
            tvWifi.setText("当前快照：" + profile.wifi.summary()
                    + "\nSSID=" + profile.wifi.ssid + "  BSSID=" + profile.wifi.bssid
                    + "  RSSI=" + profile.wifi.rssi);
            toast("WiFi 快照已采集（记得保存）");
        });

        section(c, "基站伪造");
        swCell = switchRow(c, "启用基站伪造（getAllCellInfo）", profile.cellEnabled);
        tvCell = note(c, "当前快照：" + profile.cell.summary());
        Button btnCell = button(c, getString(R.string.btn_collect_cell));
        btnCell.setOnClickListener(v -> {
            Collector.fillCell(this, profile.cell);
            tvCell.setText("当前快照：" + profile.cell.summary()
                    + (profile.cell.operator.isEmpty() ? "" : "\n运营商=" + profile.cell.operator));
            toast("基站快照已采集（记得保存）");
        });
    }

    // ------------------------------------------------------------------

    private void collect() {
        tvHint.setText("正在定位…（最多等 20 秒）");
        Collector.locate(this, (Location location, String error) -> {
            if (location == null) {
                tvHint.setText("定位失败：" + error);
                return;
            }
            double wgsLat = location.getLatitude();
            double wgsLng = location.getLongitude();
            // 系统给的是 WGS84，高德系 SDK 用 GCJ-02，不转换会偏 300~600 米
            double[] gcj = CoordConverter.wgs84ToGcj02(wgsLat, wgsLng);
            double shift = CoordConverter.distance(wgsLat, wgsLng, gcj[0], gcj[1]);

            etLat.setText(String.format(Locale.US, "%.7f", gcj[0]));
            etLng.setText(String.format(Locale.US, "%.7f", gcj[1]));
            if (location.hasAccuracy()) {
                etAccuracy.setText(String.format(Locale.US, "%.1f", location.getAccuracy()));
            }
            if (location.hasAltitude()) {
                etAltitude.setText(String.format(Locale.US, "%.1f", location.getAltitude()));
            }
            tvHint.setText(String.format(Locale.US,
                    "采集成功：%s，精度 %.0f 米\n已从 WGS84 转成 GCJ-02（偏移 %.0f 米）\n正在查询地址…",
                    location.getProvider(), location.getAccuracy(), shift));
            // 逆地理编码用原始 WGS84 坐标，查出来的地址才对得上
            geocode(wgsLat, wgsLng);
        });
    }

    private void geocodeCurrent() {
        Double lat = parse(etLat.getText().toString());
        Double lng = parse(etLng.getText().toString());
        if (lat == null || lng == null) {
            toast("先填好经纬度");
            return;
        }
        geocode(lat, lng);
    }

    private void geocode(final double lat, final double lng) {
        new Thread(() -> {
            final Address a = Collector.geocode(EditActivity.this, lat, lng);
            runOnUiThread(() -> {
                if (a == null) {
                    tvHint.setText("逆地理编码失败（需要联网；部分机型/区域没有数据）。坐标已经填好，地址可以手填或留空。");
                    return;
                }
                fillAddress(a);
                tvHint.setText("地址已填入，可自行修改。");
            });
        }).start();
    }

    private void fillAddress(Address a) {
        setIfEmpty(etProvince, a.getAdminArea());
        setIfEmpty(etCity, a.getLocality());
        setIfEmpty(etDistrict, a.getSubLocality());
        setIfEmpty(etStreet, a.getThoroughfare());
        setIfEmpty(etStreetNumber, a.getSubThoroughfare());
        setIfEmpty(etAddress, Collector.addressText(a));
        setIfEmpty(etCountry, a.getCountryName());
    }

    private void setIfEmpty(EditText et, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (et.getText().toString().trim().isEmpty()) et.setText(value);
    }

    private void save() {
        try {
            profile.name = etName.getText().toString().trim();

            Double lat = parse(etLat.getText().toString());
            Double lng = parse(etLng.getText().toString());
            if (lat == null || lng == null) {
                toast("经纬度必须填数字");
                return;
            }
            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                toast("经纬度超出范围");
                return;
            }
            profile.lat = lat;
            profile.lng = lng;

            Double radius = parse(etRadius.getText().toString());
            profile.driftRadius = radius == null ? 0 : Math.max(0, radius);

            profile.province = text(etProvince);
            profile.city = text(etCity);
            profile.district = text(etDistrict);
            profile.street = text(etStreet);
            profile.streetNumber = text(etStreetNumber);
            profile.address = text(etAddress);
            profile.poiName = text(etPoi);
            profile.aoiName = text(etAoi);
            profile.cityCode = text(etCityCode);
            profile.adCode = text(etAdCode);
            profile.country = text(etCountry);

            Double acc = parse(etAccuracy.getText().toString());
            profile.accuracy = acc == null ? 30 : acc;
            Double alt = parse(etAltitude.getText().toString());
            profile.altitude = alt == null ? 0 : alt;
            Integer lt = parseInt(etLocationType.getText().toString());
            profile.locationType = lt == null ? 1 : lt;
            profile.coordType = text(etCoordType);
            if (profile.coordType.isEmpty()) profile.coordType = "GCJ02";
            Integer tl = parseInt(etTrustedLevel.getText().toString());
            profile.trustedLevel = tl == null ? 1 : tl;
            Integer cs = parseInt(etConScenario.getText().toString());
            profile.conScenario = cs == null ? 0 : cs;

            // 完整地址留空时按省市区街道拼一份，两条读取路径共用同一份数据
            if (profile.address.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append(profile.province).append(profile.city)
                        .append(profile.district).append(profile.street)
                        .append(profile.streetNumber);
                profile.address = sb.toString();
            }

            profile.wifiEnabled = swWifi.isChecked();
            profile.cellEnabled = swCell.isChecked();
            profile.enabled = true;

            profiles.put(profile);
            profiles.current = profile.id;
            ConfigStore.save(this, profiles);

            toast("已保存，并设为当前配置");
            finish();
        } catch (Throwable t) {
            toast("保存失败: " + t);
        }
    }

    // ------------------------------------------------------------------ 控件工具

    private void section(LinearLayout parent, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(14);
        tv.setTextColor(0xFF1565C0);
        tv.getPaint().setFakeBoldText(true);
        tv.setPadding(0, dp(18), 0, dp(4));
        parent.addView(tv);
    }

    private EditText field(LinearLayout parent, String label, String value, int inputType) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setTextColor(0xFF666666);
        tv.setPadding(0, dp(8), 0, 0);
        parent.addView(tv);

        EditText et = new EditText(this);
        et.setText(value == null ? "" : value);
        et.setTextSize(14);
        et.setSingleLine(true);
        et.setInputType(inputType);
        parent.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    private Button button(LinearLayout parent, String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        parent.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private Switch switchRow(LinearLayout parent, String label, boolean checked) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(13);
        sw.setChecked(checked);
        parent.addView(sw);
        return sw;
    }

    private TextView note(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(0xFF888888);
        tv.setPadding(0, dp(4), 0, dp(4));
        parent.addView(tv);
        return tv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String text(EditText et) {
        return et.getText().toString().trim();
    }

    private String fmt(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    private Double parse(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable t) {
            return null;
        }
    }

    private Integer parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return null;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
