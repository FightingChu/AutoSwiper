package com.example.autoswiper;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 设置页：滑动模式（按次数 / 按时长）与数值，
 * 并引导开启无障碍服务（开启后悬浮窗自动出现）。
 */
public class MainActivity extends AppCompatActivity {

    private RadioGroup modeGroup;
    private RadioButton modeCount;
    private RadioButton modeTime;
    private EditText valueInput;
    private EditText reverseInput;
    private TextView serviceState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        modeGroup = findViewById(R.id.mode_group);
        modeCount = findViewById(R.id.mode_count);
        modeTime = findViewById(R.id.mode_time);
        valueInput = findViewById(R.id.value_input);
        reverseInput = findViewById(R.id.reverse_input);
        serviceState = findViewById(R.id.service_state);
        Button saveBtn = findViewById(R.id.save_btn);
        Button accBtn = findViewById(R.id.acc_btn);

        // 回显已保存设置
        if (Prefs.MODE_TIME.equals(Prefs.getMode(this))) {
            modeTime.setChecked(true);
        } else {
            modeCount.setChecked(true);
        }
        valueInput.setText(String.valueOf(Prefs.getValue(this)));
        reverseInput.setText(String.valueOf(Prefs.getReverseCount(this)));

        saveBtn.setOnClickListener(v -> save());
        accBtn.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshServiceState();
    }

    private void save() {
        String raw = valueInput.getText().toString().trim();
        if (TextUtils.isEmpty(raw)) {
            Toast.makeText(this, "请输入数值（0 = 无限）", Toast.LENGTH_SHORT).show();
            return;
        }
        int v;
        try {
            v = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "数值不合法", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean isTime = modeGroup.getCheckedRadioButtonId() == R.id.mode_time;
        Prefs.setMode(this, isTime ? Prefs.MODE_TIME : Prefs.MODE_COUNT);
        Prefs.setValue(this, v);

        // 到底反弹次数（0 = 不反弹，保持纯向下滑）
        String rawR = reverseInput.getText().toString().trim();
        int r = 0;
        if (!TextUtils.isEmpty(rawR)) {
            try {
                r = Integer.parseInt(rawR);
            } catch (NumberFormatException e) {
                Toast.makeText(this, "反弹次数不合法", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Prefs.setReverseCount(this, r);

        String unit = isTime ? "秒" : "次";
        Toast.makeText(this,
                "已保存：" + (v == 0 ? "无限滑动" : v + " " + unit + "（1 秒 1 次）")
                        + " · 到底反弹 " + r + " 次",
                Toast.LENGTH_SHORT).show();
    }

    private void refreshServiceState() {
        boolean on = isServiceEnabled();
        serviceState.setText(on
                ? "✅ 无障碍服务已开启，悬浮窗应已显示"
                : "❌ 无障碍服务未开启：点击下方按钮 → 找到「AutoSwiper」→ 开启");
    }

    private boolean isServiceEnabled() {
        AccessibilityManager am =
                (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled()) return false;
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName() + "/");
    }
}
