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
}
