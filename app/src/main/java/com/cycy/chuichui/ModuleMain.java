package com.cycy.chuichui;

import com.cycy.chuichui.hook.AmapHooks;
import com.cycy.chuichui.hook.CellHooks;
import com.cycy.chuichui.hook.WifiHooks;
import com.cycy.chuichui.store.ConfigReader;
import com.cycy.chuichui.util.Logger;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed API 102 模块入口。
 *
 * 老式模块是 IXposedHookLoadPackage + assets/xposed_init，
 * 新式是继承 XposedModule + META-INF/xposed/java_init.list。
 * targetApiVersion=102 时 de.robv.* 那套 API 会被框架禁用，所以这里一个都不能用。
 */
public class ModuleMain extends XposedModule {

    private static final String TARGET_PACKAGE = "com.alibaba.android.rimet";

    /** 一个进程可能加载多个包，按包名去重，避免重复安装 hook */
    private static final Set<String> HANDLED =
            Collections.synchronizedSet(new HashSet<String>());

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        Logger.attach(this);
        ConfigReader.attach(this);
        try {
            Logger.i("模块加载 process=" + param.getProcessName()
                    + " framework=" + getFrameworkName()
                    + "(" + getFrameworkVersion() + ")"
                    + " api=" + getApiVersion()
                    + " props=" + getFrameworkProperties());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        Logger.attach(this);
        ConfigReader.attach(this);

        String pkg = param.getPackageName();
        if (!TARGET_PACKAGE.equals(pkg)) return;
        if (!HANDLED.add(pkg)) return;

        try {
            Logger.i("目标包就绪: " + pkg + "，开始安装 hook");
            installHooks(param.getClassLoader());
        } catch (Throwable t) {
            Logger.e("安装 hook 失败", t);
        }
    }

    private void installHooks(ClassLoader cl) {
        int amap = 0;
        int wifi = 0;
        int cell = 0;

        // 0) 拿目标 App 的 Context —— 后面调 ContentProvider 必须用它
        installContextHook(cl);

        try {
            amap = AmapHooks.install(this, cl);
        } catch (Throwable t) {
            Logger.e("定位 hook 异常", t);
        }
        try {
            wifi = WifiHooks.install(this);
        } catch (Throwable t) {
            Logger.e("WiFi hook 异常", t);
        }
        try {
            cell = CellHooks.install(this);
        } catch (Throwable t) {
            Logger.e("基站 hook 异常", t);
        }

        Logger.i("hook 安装完成: 定位 " + amap + " / WiFi " + wifi + " / 基站 " + cell);

        // 主动读一次配置，把来源打到日志里，方便判断通道是否通
        try {
            ConfigReader.current();
            Logger.i("当前配置通道: " + ConfigReader.lastSource());
        } catch (Throwable t) {
            Logger.w("读取配置失败: " + t);
        }
    }

    /**
     * hook Application.onCreate，把 Application 实例存起来。
     * 目标进程里拿 Context 只有两条路：这个 hook，或者反射 currentApplication；
     * 后者在 hook 安装得太早时可能返回 null，所以两个都留着。
     */
    private void installContextHook(ClassLoader cl) {
        try {
            Class<?> appCls = Class.forName("android.app.Application", false, cl);
            java.lang.reflect.Method onCreate = appCls.getDeclaredMethod("onCreate");
            onCreate.setAccessible(true);
            hook(onCreate).setId("chuichui_app_ctx").intercept(chain -> {
                Object app = chain.getThisObject();
                if (app instanceof android.content.Context) {
                    ConfigReader.setAppContext((android.content.Context) app);
                    Logger.i("Application Context 就绪: " + app.getClass().getName());
                    try {
                        ConfigReader.current();
                        Logger.i("配置来源: " + ConfigReader.lastSource());
                    } catch (Throwable t) {
                        Logger.w("读取配置失败: " + t);
                    }
                }
                return chain.proceed();
            });
            Logger.i("Application.onCreate hook 已安装");
        } catch (Throwable t) {
            Logger.w("hook Application.onCreate 失败: " + t);
        }
    }
}
