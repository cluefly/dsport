package de.robv.android.xposed.callbacks;

import de.robv.android.xposed.IXposedHookLoadPackage;

public abstract class XC_LoadPackage extends XCallback implements IXposedHookLoadPackage {
    public static final class LoadPackageParam extends XCallback.Param {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }

    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {}

    @Override
    protected void call(Param param) throws Throwable {}
}
