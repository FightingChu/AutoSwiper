package com.example.autoswiper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
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
 * 通用自动滑动服务（AutoSwiper v1.1）
 *
 * - 每 1 秒执行一次滑动，任意 App 页面通用；
 * - 每次触点随机（起点 X/Y、终点 X/Y、轨迹弧度、时长均随机），不固定一个点；
 * - 主方向「向下滑」（看下面，原 v1.0 行为）；
 * - 到底检测：连续 2 次滑动页面内容无变化 → 判定到底，自动切换到反向「向上滑」（看上面）；
 *   反向滑动执行「可设置的次数」后，回到主方向继续 —— 如此来回循环自动刷
 *   （反弹次数 = 0 时关闭该特性，退化为纯向下滑）；
 * - 悬浮窗常驻：一键 开始/停止；可拖动；实时显示剩余次数 / 反弹状态；
 * - 设定次数滑完自动停止，但悬浮窗保留，可再次一键开始。
 *
 * 悬浮窗使用 TYPE_ACCESSIBILITY_OVERLAY：无障碍服务专用层，
 * 无需 SYSTEM_ALERT_WINDOW 权限（避开国产 ROM 悬浮窗权限限制）。
 */
public class SwipeService extends AccessibilityService {

    private static final String TAG = "AutoSwiper";

    /** 固定节拍：1 秒 1 次。 */
    private static final long TICK_MS = 1000;

    /** 连续多少次页面无变化判定为「到底」。 */
    private static final int STABLE_THRESHOLD = 2;

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
    private View overlay;
    private WindowManager.LayoutParams overlayLp;
    private TextView statusText;
    private Button toggleBtn;

    // ---------- 生命周期 ----------

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        handler = new Handler(Looper.getMainLooper());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        dm = getResources().getDisplayMetrics();
        showOverlay();
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
        if (handler != null && tickRunnable != null) {
            handler.removeCallbacks(tickRunnable);
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

    // ---------- 悬浮窗 ----------

    private void showOverlay() {
        if (wm == null || overlay != null) return;

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
        });
        panel.addView(toggleBtn);

        overlayLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        overlayLp.gravity = Gravity.TOP | Gravity.START;
        overlayLp.x = (int) (12 * d);
        overlayLp.y = (int) (120 * d);

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

        overlay = panel;
        try {
            wm.addView(overlay, overlayLp);
        } catch (Exception e) {
            Log.e(TAG, "overlay add failed", e);
            overlay = null;
            return;
        }
        updateUi();
    }

    private void removeOverlay() {
        if (wm != null && overlay != null) {
            try {
                wm.removeView(overlay);
            } catch (Exception ignore) {
            }
            overlay = null;
            statusText = null;
            toggleBtn = null;
        }
    }

    /** TYPE_ACCESSIBILITY_OVERLAY 免权限；API<27 回退 TYPE_PHONE。 */
    private int overlayWindowType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void updateUi() {
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
