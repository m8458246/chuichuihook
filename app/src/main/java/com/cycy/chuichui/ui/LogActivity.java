package com.cycy.chuichui.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import com.cycy.chuichui.R;
import com.cycy.chuichui.util.HookLog;
import com.cycy.chuichui.util.Logger;

public class LogActivity extends Activity {

    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        logText = findViewById(R.id.logText);

        Button refresh = findViewById(R.id.btnRefresh);
        Button clear = findViewById(R.id.btnClear);

        refresh.setOnClickListener(v -> load());
        clear.setOnClickListener(v -> {
            HookLog.clear(this);
            load();
        });

        load();
    }

    private void load() {
        // 主日志：/sdcard/Download/chuichuihook/hook.log（目标进程用 MediaStore 直接写）
        String text = Logger.readPublicLog(this);
        if (text == null || text.trim().isEmpty()) {
            // 兜底：目标进程写不了公共目录时通过广播回传到这里
            text = HookLog.read(this);
        }
        if (text == null || text.trim().isEmpty()) {
            logText.setText("（暂无日志）\n\n"
                    + "1. 确认 LSPosed 里已启用本模块，作用域勾选了「钉钉」\n"
                    + "2. 强行停止钉钉后重新打开\n"
                    + "3. 回本页点「刷新」\n\n"
                    + "日志文件：/sdcard/Download/chuichuihook/hook.log\n"
                    + "也可以直接用 logcat 看：logcat -s ChuiChui:V");
        } else {
            logText.setText(text);
        }
    }
}
