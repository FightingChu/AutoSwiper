package com.example.autoswiper;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 设置存取：滑动模式（按次数 / 按时长）与数值。
 * 因为固定 1 秒滑 1 次，「时长 N 秒」等价于「N 次」，服务端统一按次数执行。
 */
public final class Prefs {

    private static final String FILE = "autoswiper_prefs";
    private static final String KEY_MODE = "mode";       // count | time
    private static final String KEY_VALUE = "value";     // 次数或秒数
    private static final String KEY_REVERSE = "reverse"; // 到底后反向(向上)滑动次数
    private static final String KEY_OVERLAY = "overlay_on"; // 悬浮窗是否显示

    public static final String MODE_COUNT = "count";
    public static final String MODE_TIME = "time";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String getMode(Context c) {
        return sp(c).getString(KEY_MODE, MODE_COUNT);
    }

    public static void setMode(Context c, String mode) {
        sp(c).edit().putString(KEY_MODE, mode).apply();
    }

    /** 数值：次数或秒数。0 表示无限（手动停止）。默认 100。 */
    public static int getValue(Context c) {
        return sp(c).getInt(KEY_VALUE, 100);
    }

    public static void setValue(Context c, int v) {
        sp(c).edit().putInt(KEY_VALUE, Math.max(0, v)).apply();
    }

    /** 本次任务总滑动次数（time 模式下 1 秒 1 次，秒数即次数）。0=无限。 */
    public static int getTotalSwipes(Context c) {
        return getValue(c);
    }

    /** 到底后反向（向上滑）次数。0 = 不反弹，保持纯向下滑。默认 10。 */
    public static int getReverseCount(Context c) {
        return sp(c).getInt(KEY_REVERSE, 10);
    }

    public static void setReverseCount(Context c, int v) {
        sp(c).edit().putInt(KEY_REVERSE, Math.max(0, v)).apply();
    }

    /** 悬浮窗是否显示（由 App 内按钮控制）。默认 false（开启无障碍不直接弹窗）。 */
    public static boolean getOverlayOn(Context c) {
        return sp(c).getBoolean(KEY_OVERLAY, false);
    }

    public static void setOverlayOn(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_OVERLAY, on).apply();
    }
}
