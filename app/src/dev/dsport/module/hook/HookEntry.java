package dev.dsport.module.hook;

import dev.dsport.module.StepMath;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public final class HookEntry implements IXposedHookLoadPackage {
    static final String SELF = "dev.dsport.module";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        // This process shows the raw sensors. Hooking it would hide the real values.
        if (SELF.equals(lpparam.packageName)) {
            return;
        }
        // exsystemservice and healthservice share this process. Hooking the framework
        // method from both class loaders would add the offset twice.
        Class<?> gate;
        try {
            gate = Class.forName("android.hardware.SensorManager");
        } catch (ClassNotFoundException error) {
            XposedBridge.log(error);
            return;
        }
        synchronized (gate) {
            if ("1".equals(System.getProperty("dsport.hooked"))) {
                return;
            }
            if (!install(lpparam.classLoader, lpparam.packageName)) {
                return;
            }
            System.setProperty("dsport.hooked", "1");
        }
    }

    private static boolean install(ClassLoader classLoader, String packageName) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.hardware.SystemSensorManager$SensorEventQueue",
                    classLoader,
                    "dispatchSensorEvent",
                    int.class,
                    float[].class,
                    int.class,
                    long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            SensorDispatch.onEvent(packageName, param.thisObject, param.args);
                        }
                    });
            XposedBridge.log("DSport: hooked " + packageName);
            if (StepMath.releasesCredit(packageName)) {
                SensorDispatch.startTicker();
            }
            return true;
        } catch (Throwable error) {
            XposedBridge.log("DSport: hook failed for " + packageName);
            XposedBridge.log(error);
            return false;
        }
    }
}
