package com.example.autoswiper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 通用自动滑动服务（AutoSwiper v1.2）
 *
 * - 每 1 秒执行一次滑动，任意 App 页面通用；
 * - 每次触点随机（起点 X/Y、终点 X/Y、轨迹弧度、时长均随机），不固定一个点；
 * - 主方向「向下滑」（看下面，原 v1.0 行为）；
 * - 到底检测：连续 2 次滑动页面内容无变化 → 判定到底，自动切换到反向「向上滑」（看上面）；
 *   反向滑动执行「可设置的次数」后，回到主方向继续 —— 如此来回循环自动刷
 *   （反弹次数 = 0 时关闭该特性，退化为纯向下滑）；
 * - 悬浮窗：由 App 内按钮「显示/隐藏」控制，开启无障碍服务时不再自动弹窗；
 *   悬浮窗静止超过 3 秒且贴边时，自动收缩为小圆点，点击圆点再次展开；
 * - 设定次数滑完自动停止，但悬浮窗保留，可再次一键开始。
 *
 * 悬浮窗使用 TYPE_ACCESSIBILITY_OVERLAY：无障碍服务专用层，
 * 无需 SYSTEM_ALERT_WINDOW 权限（避开国产 ROM 悬浮窗权限限制）。
 */
public class SwipeService extends AccessibilityService {

    public static final String ACTION_SHOW_FLOAT = "com.example.autoswiper.SHOW_FLOAT";
    public static final String ACTION_HIDE_FLOAT = "com.example.autoswiper.HIDE_FLOAT";

    private static final String TAG = "AutoSwiper";

    /** 固定节拍：1 秒 1 次。 */
    private static final long TICK_MS = 1000;

    /** 连续多少次页面无变化判定为「到底」。 */
    private static final int STABLE_THRESHOLD = 2;

    /** 悬浮窗静止多久后尝试自动收缩（毫秒）。 */
    private static final long IDLE_COLLAPSE_MS = 3000;

    /** 判定「贴边」的屏幕边缘留白阈值（dp）。 */
    private static final float EDGE_MARGIN_DP = 24;

    private Handler handler;
    private Runnable tickRunnable;

    private boolean running = false;
    /** 本轮任务总次数（0=无限）与剩余次数。 */
    private int total = 0;
    private int remaining = 0;
    private int doneCount = 0;

    /** 到底检测状态。 */
    private String lastFingerprint = "";
    private int stableCount = 0;

    /** 反弹（反向滑动）状态。 */
    private int reverseRemaining = 0;
    private boolean reverseMode = false;

    private WindowManager wm;
    private DisplayMetrics dm;

    // ---------- 悬浮窗状态 ----------
    private View overlay;        // 当前挂在窗口上的视图（panel 或 dot）
    private View panelView;      // 展开态：完整面板
    private View dotView;        // 收缩态：小圆点
    private WindowManager.LayoutParams overlayLp;
    private TextView statusText;
    private Button toggleBtn;

    private boolean overlayShown = false;  // 是否显示（HIDE 时为 false）
    private boolean collapsed = false;     // 是否处于收缩小圆点态
    private long lastInteract = 0;         // 上次交互时间（用于自动收缩）

    private BroadcastReceiver floatReceiver;

    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            if (!overlayShown || collapsed) return;
            long idle = System.currentTimeMillis() - lastInteract;
            if (idle >= IDLE_COLLAPSE_MS && isNearEdge()) {
                collapseOverlay();
                return;
            }
            // 未满足：继续轮询，直到收缩或再次交互
            if (handler != null) {
                handler.postDelayed(this, 500);
            }
        }
    };

    // ---------- 生命周期 ----------

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        handler = new Handler(Looper.getMainLooper());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        dm = getResources().getDisplayMetrics();

        // 注册悬浮窗显隐广播：开启无障碍服务时【不】自动弹窗，由 App 按钮控制
        floatReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                String a = i.getAction();
                if (ACTION_SHOW_FLOAT.equals(a)) {
                    showOverlay();
                } else if (ACTION_HIDE_FLOAT.equals(a)) {
                    hideOverlay();
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_SHOW_FLOAT);
        f.addAction(ACTION_HIDE_FLOAT);
        registerReceiver(floatReceiver, f);

        startLoop();
        Log.d(TAG, "service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 通用滑动不依赖具体事件
    }

    @Override
    public void onInterrupt() {
        stopTask("服务被中断");
    }

    @Override
    public void onDestroy() {
        if (floatReceiver != null) {
            try {
                unregisterReceiver(floatReceiver);
            } catch (Exception ignore) {
            }
            floatReceiver = null;
        }
        if (handler != null) {
            handler.removeCallbacks(tickRunnable);
            handler.removeCallbacks(idleCheck);
        }
        removeOverlay();
        super.onDestroy();
    }

    // ---------- 任务控制 ----------

    private void startTask() {
        total = Prefs.getTotalSwipes(this);
        remaining = total;
        doneCount = 0;
        stableCount = 0;
        lastFingerprint = "";
        reverseRemaining = 0;
        reverseMode = false;
        running = true;
        updateUi();
    }

    private void stopTask(String reason) {
        running = false;
        updateUi();
        if (reason != null) {
            Log.d(TAG, "stop: " + reason);
        }
    }

    private void startLoop() {
        tickRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    tick();
                } catch (Exception e) {
                    Log.e(TAG, "tick error", e);
                }
                if (handler != null) {
                    handler.postDelayed(this, TICK_MS);
                }
            }
        };
        handler.postDelayed(tickRunnable, TICK_MS);
    }

    private void tick() {
        if (!running) return;

        // 总次数已耗尽（仅有限模式）：自动停止，悬浮窗保留
        if (total > 0 && remaining <= 0) {
            running = false;
            updateUi();
            return;
        }

        // ---------- 反弹阶段：执行反向「向上滑」（看上面） ----------
        if (reverseRemaining > 0) {
            boolean ok = performRandomSwipeDown();
            if (ok) {
                doneCount++;
                if (total > 0) remaining--;
            }
            reverseRemaining--;
            if (reverseRemaining <= 0) {
                // 反弹结束，回到主方向继续（循环）
                reverseMode = false;
                stableCount = 0;
                lastFingerprint = "";
            }
            updateUi();
            return;
        }

        // ---------- 主阶段：执行「向下滑」（看下面），并检测是否到底 ----------
        String cur = fingerprint();
        if (!lastFingerprint.isEmpty() && cur.equals(lastFingerprint)) {
            stableCount++;
        } else {
            stableCount = 0;
        }
        lastFingerprint = cur;

        boolean ok = performRandomSwipeUp();
        if (ok) {
            doneCount++;
            if (total > 0) remaining--;
        }

        // 连续 N 次页面无变化 → 判定到底，触发反弹
        if (stableCount >= STABLE_THRESHOLD) {
            int rc = Prefs.getReverseCount(this);
            if (rc > 0) {
                reverseRemaining = rc;
                reverseMode = true;
            }
            // 无论是否反弹，都重置检测，避免连续误判
            stableCount = 0;
            lastFingerprint = "";
        }
        updateUi();
    }

    /**
     * 随机触点「向下滑」（看下面）：起点在屏下 65%~82%，终点在屏上 18%~35%，
     * 终点 X 带随机横向偏移，中段带随机弧度，手势时长 250~450ms。
     */
    private boolean performRandomSwipeUp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        int w = dm.widthPixels;
        int h = dm.heightPixels;

        int startX = (int) (w * (0.30 + Math.random() * 0.40));   // 0.30~0.70
        int startY = (int) (h * (0.65 + Math.random() * 0.17));   // 0.65~0.82
        int endX = clamp(startX + (int) ((Math.random() - 0.5) * w * 0.12),
                (int) (w * 0.15), (int) (w * 0.85));
        int endY = (int) (h * (0.18 + Math.random() * 0.17));     // 0.18~0.35
        int duration = 250 + (int) (Math.random() * 200);         // 250~450ms

        return dispatchSwipe(startX, startY, endX, endY, duration, w);
    }

    /**
     * 随机触点「向上滑」（看上面）：与向下滑相反，起点在屏上 18%~35%，
     * 终点在屏下 65%~82%，其余随机逻辑一致。
     */
    private boolean performRandomSwipeDown() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        int w = dm.widthPixels;
        int h = dm.heightPixels;

        int startX = (int) (w * (0.30 + Math.random() * 0.40));   // 0.30~0.70
        int startY = (int) (h * (0.18 + Math.random() * 0.17));   // 0.18~0.35
        int endX = clamp(startX + (int) ((Math.random() - 0.5) * w * 0.12),
                (int) (w * 0.15), (int) (w * 0.85));
        int endY = (int) (h * (0.65 + Math.random() * 0.17));     // 0.65~0.82
        int duration = 250 + (int) (Math.random() * 200);         // 250~450ms

        return dispatchSwipe(startX, startY, endX, endY, duration, w);
    }

    private boolean dispatchSwipe(int startX, int startY, int endX, int endY,
                                  int duration, int w) {
        Path path = new Path();
        path.moveTo(startX, startY);
        int midX = (startX + endX) / 2 + (int) ((Math.random() - 0.5) * w * 0.06);
        int midY = (startY + endY) / 2;
        path.quadTo(midX, midY, endX, endY);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        return dispatchGesture(builder.build(), null, null);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * 页面内容指纹：采样根节点可见文本拼接。连续多次相同 → 页面未变 → 判定到底。
     * 仅取前若干字符，控制开销（1 秒 1 次足够）。
     */
    private String fingerprint() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "none";
        StringBuilder sb = new StringBuilder();
        traverse(root, sb, 0);
        try {
            root.recycle();
        } catch (Exception ignore) {
        }
        String s = sb.toString();
        return s.length() > 1500 ? s.substring(0, 1500) : s;
    }

    private void traverse(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 10 || sb.length() >= 1500) return;
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) {
            sb.append(t).append('|');
        }
        int c = n.getChildCount();
        for (int i = 0; i < c; i++) {
            traverse(n.getChild(i), sb, depth + 1);
        }
    }

    // ---------- 悬浮窗显隐 ----------

    /** 由 App 按钮发送 SHOW_FLOAT 触发：显示悬浮窗（展开态）。 */
    private void showOverlay() {
        if (wm == null || overlayShown) return;
        ensureViewsBuilt();
        overlayShown = true;
        collapsed = false;
        swapView(panelView);
        noteInteraction();
        updateUi();
    }

    /** 由 App 按钮发送 HIDE_FLOAT 触发：隐藏整个悬浮窗（保留引用，便于再次显示）。 */
    private void hideOverlay() {
        overlayShown = false;
        collapsed = false;
        if (handler != null) handler.removeCallbacks(idleCheck);
        if (overlay != null) {
            try {
                wm.removeView(overlay);
            } catch (Exception ignore) {
            }
            overlay = null;
        }
    }

    /** 彻底移除（服务销毁时），清空所有引用。 */
    private void removeOverlay() {
        if (wm != null && overlay != null) {
            try {
                wm.removeView(overlay);
            } catch (Exception ignore) {
            }
        }
        overlay = null;
        panelView = null;
        dotView = null;
        statusText = null;
        toggleBtn = null;
    }

    /** 在 panel 与 dot 之间切换当前窗口内容（位置 x/y 保持）。 */
    private void swapView(View v) {
        if (overlay != null) {
            try {
                wm.removeView(overlay);
            } catch (Exception ignore) {
            }
        }
        overlay = v;
        try {
            wm.addView(overlay, overlayLp);
        } catch (Exception e) {
            Log.e(TAG, "overlay add failed", e);
            overlay = null;
        }
    }

    /** 收缩为小圆点。 */
    private void collapseOverlay() {
        if (!overlayShown || collapsed) return;
        collapsed = true;
        if (handler != null) handler.removeCallbacks(idleCheck);
        swapView(dotView);
    }

    /** 从圆点展开回完整面板。 */
    private void expandOverlay() {
        if (!overlayShown || !collapsed) return;
        collapsed = false;
        swapView(panelView);
        noteInteraction();
        updateUi();
    }

    /** 记录一次交互，重置「静止计时」。 */
    private void noteInteraction() {
        lastInteract = System.currentTimeMillis();
        if (handler != null) {
            handler.removeCallbacks(idleCheck);
            handler.postDelayed(idleCheck, IDLE_COLLAPSE_MS);
        }
    }

    /** 当前窗口是否贴边（距任一屏幕边缘 < 阈值）。 */
    private boolean isNearEdge() {
        if (panelView == null || overlayLp == null) return false;
        int w = panelView.getWidth();
        int h = panelView.getHeight();
        if (w == 0 || h == 0) return false;
        float th = EDGE_MARGIN_DP * dm.density;
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        return overlayLp.x <= th
                || overlayLp.y <= th
                || overlayLp.x + w >= screenW - th
                || overlayLp.y + h >= screenH - th;
    }

    private void ensureViewsBuilt() {
        if (overlayLp == null) {
            float d = dm.density;
            overlayLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayWindowType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            overlayLp.gravity = Gravity.TOP | Gravity.START;
            overlayLp.x = (int) (80 * d);
            overlayLp.y = (int) (160 * d);
        }
        if (panelView == null) buildPanel();
        if (dotView == null) buildDot();
    }

    private void buildPanel() {
        float d = dm.density;
        int pad = (int) (10 * d);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xDD222222);
        bg.setCornerRadius(12 * d);
        panel.setBackground(bg);

        // 拖动区（标题栏）
        TextView title = new TextView(this);
        title.setText("AutoSwiper ↕ 拖我");
        title.setTextColor(0xFFAAAAAA);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        title.setPadding(0, 0, 0, (int) (6 * d));
        panel.addView(title);

        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusText.setPadding(0, 0, 0, (int) (6 * d));
        panel.addView(statusText);

        toggleBtn = new Button(this);
        toggleBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        toggleBtn.setAllCaps(false);
        toggleBtn.setOnClickListener(v -> {
            if (running) {
                stopTask("手动停止");
            } else {
                startTask();
            }
            noteInteraction();
        });
        panel.addView(toggleBtn);

        // 整个面板可拖动；短按（位移极小）不拦截，按钮仍可点
        panel.setOnTouchListener(new View.OnTouchListener() {
            private int downX, downY, lpX, lpY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = (int) e.getRawX();
                        downY = (int) e.getRawY();
                        lpX = overlayLp.x;
                        lpY = overlayLp.y;
                        dragging = false;
                        noteInteraction();
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) e.getRawX() - downX;
                        int dy = (int) e.getRawY() - downY;
                        if (!dragging && (Math.abs(dx) > 12 || Math.abs(dy) > 12)) {
                            dragging = true;
                        }
                        if (dragging) {
                            overlayLp.x = lpX + dx;
                            overlayLp.y = lpY + dy;
                            try {
                                wm.updateViewLayout(overlay, overlayLp);
                            } catch (Exception ignore) {
                            }
                            return true;
                        }
                        return false;
                    default:
                        return dragging;
                }
            }
        });

        panelView = panel;
    }

    private void buildDot() {
        float d = dm.density;
        int size = (int) (44 * d);

        TextView dot = new TextView(this);
        dot.setText("↕");
        dot.setTextColor(Color.WHITE);
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        dot.setGravity(Gravity.CENTER);
        dot.setMinWidth(size);
        dot.setMinHeight(size);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(0xDD444444);
        dot.setBackground(dotBg);
        dot.setOnClickListener(v -> expandOverlay());

        dotView = dot;
    }

    /** TYPE_ACCESSIBILITY_OVERLAY 免权限；API<27 回退 TYPE_PHONE。 */
    private int overlayWindowType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void updateUi() {
        if (!overlayShown || collapsed) return;
        if (statusText == null || toggleBtn == null) return;
        String mode = Prefs.getMode(this);
        int value = Prefs.getValue(this);
        if (running) {
            if (reverseMode) {
                statusText.setText("反弹中（向上滑）剩余 " + reverseRemaining + " 次");
            } else if (total > 0) {
                statusText.setText("运行中：剩余 " + remaining + " 次 / 共 " + total);
            } else {
                statusText.setText("运行中：已滑 " + doneCount + " 次（无限模式）");
            }
            toggleBtn.setText("停止");
        } else {
            if (doneCount > 0 && total > 0 && remaining <= 0) {
                statusText.setText("已完成 " + doneCount + " 次，自动停止");
            } else if (doneCount > 0) {
                statusText.setText("已停止（本轮已滑 " + doneCount + " 次）");
            } else {
                String setting = Prefs.MODE_TIME.equals(mode)
                        ? (value == 0 ? "无限" : value + " 秒")
                        : (value == 0 ? "无限" : value + " 次");
                statusText.setText("待机 · 设定：" + setting
                        + " · 反弹 " + Prefs.getReverseCount(this) + " 次");
            }
            toggleBtn.setText("开始");
        }
    }
}
