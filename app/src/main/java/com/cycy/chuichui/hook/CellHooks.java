package com.cycy.chuichui.hook;

import android.telephony.CellInfo;

import com.cycy.chuichui.model.CellSnapshot;
import com.cycy.chuichui.model.Profile;
import com.cycy.chuichui.store.ConfigReader;
import com.cycy.chuichui.util.Logger;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/** 基站信息 hook：伪造 getAllCellInfo 的返回 */
public final class CellHooks {

    private CellHooks() {
    }

    public static int install(XposedModule module) {
        int n = 0;
        try {
            Class<?> cls = Class.forName("android.telephony.TelephonyManager");
            Method m = cls.getDeclaredMethod("getAllCellInfo");
            m.setAccessible(true);
            module.hook(m).setId("chuichui_cell_info").intercept(chain -> {
                Profile p = ConfigReader.current();
                if (p == null || !p.cellEnabled) return chain.proceed();
                List<CellInfo> list = CellSnapshot.restore(p.cell);
                return list != null ? list : chain.proceed();
            });
            n++;
        } catch (Throwable t) {
            Logger.w("hook getAllCellInfo 失败: " + t);
        }
        return n;
    }
}
