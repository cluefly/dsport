package de.robv.android.xposed.callbacks;

public abstract class XCallback {
    public int priority;

    public static abstract class Param {}

    protected void call(Param param) throws Throwable {}
}
