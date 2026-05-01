package com.miaojizhang.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

public class FloatingWindowService extends Service {
    private WindowManager windowManager;
    private View floatView;
    private View closeMenuView;
    private WindowManager.LayoutParams params;
    private WindowManager.LayoutParams closeMenuParams;
    private int startX;
    private int startY;
    private float downX;
    private float downY;
    private boolean moved;
    private boolean longPressed;
    private int bubbleSize;
    private int hideOffset;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnable;
    private Runnable hideMenuRunnable;
    private static final float IDLE_ALPHA = 0.70f;
    private Runnable dimRunnable = () -> { if (floatView != null && closeMenuView == null) floatView.setAlpha(IDLE_ALPHA); };

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        createFloatingButton();
    }

    private void createFloatingButton() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null || floatView != null) return;

        float density = getResources().getDisplayMetrics().density;
        bubbleSize = (int) (56 * density);
        hideOffset = (int) (36 * density);

        TextView bubble = new TextView(this);
        bubble.setText("+");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(30);
        bubble.setGravity(Gravity.CENTER);
        bubble.setIncludeFontPadding(false);
        bubble.setBackground(makeBubbleBackground());
        bubble.setElevation(7 * density);
        bubble.setAlpha(IDLE_ALPHA);
        floatView = bubble;

        params = new WindowManager.LayoutParams();
        params.width = bubbleSize;
        params.height = bubbleSize;
        params.gravity = Gravity.TOP | Gravity.START;
        params.format = PixelFormat.TRANSLUCENT;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            params.type = WindowManager.LayoutParams.TYPE_PHONE;
        }

        SharedPreferences sp = getSharedPreferences("miaojizhang_native", MODE_PRIVATE);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int savedX = sp.getInt("float_x", sw - bubbleSize + hideOffset);
        int savedY = sp.getInt("float_y", Math.max((int)(100 * density), sh / 2 - bubbleSize));
        params.x = clampX(savedX);
        params.y = clampY(savedY);

        floatView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (floatView != null) floatView.setAlpha(1f);
                    hideCloseMenuDelayed(0);
                    startX = params.x;
                    startY = params.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    moved = false;
                    longPressed = false;
                    startLongPress(v);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - downX);
                    int dy = (int) (event.getRawY() - downY);
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        moved = true;
                        cancelLongPress();
                    }
                    params.x = startX + dx;
                    params.y = startY + dy;
                    try { windowManager.updateViewLayout(floatView, params); } catch (Exception ignored) {}
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    cancelLongPress();
                    if (longPressed) {
                        snapToEdge();
                        return true;
                    }
                    if (!moved) {
                        openAddRecord();
                    } else {
                        snapToEdge();
                    }
                    return true;
            }
            return false;
        });

        try {
            windowManager.addView(floatView, params);
            snapToEdge();
        } catch (Exception e) {
            floatView = null;
            stopSelf();
        }
    }

    private GradientDrawable makeBubbleBackground() {
        int primary = readThemeColor();
        int light = mixColor(primary, Color.WHITE, 0.22f);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{light, primary});
        bg.setShape(GradientDrawable.OVAL);
        return bg;
    }

    private int readThemeColor() {
        String color = getSharedPreferences("miaojizhang_native", MODE_PRIVATE).getString("float_color", "#12b981");
        try { return Color.parseColor(color); } catch (Exception e) { return Color.rgb(18, 185, 129); }
    }

    private int mixColor(int a, int b, float t) {
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return Color.rgb(r, g, bl);
    }

    private int clampX(int x) {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int minX = -hideOffset;
        int maxX = sw - bubbleSize + hideOffset;
        return Math.max(minX, Math.min(maxX, x));
    }

    private int clampY(int y) {
        int sh = getResources().getDisplayMetrics().heightPixels;
        return Math.max(0, Math.min(sh - bubbleSize, y));
    }

    private void startLongPress(View v) {
        cancelLongPress();
        longPressRunnable = () -> {
            longPressed = true;
            try { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); } catch (Exception ignored) {}
            showCloseMenu();
        };
        handler.postDelayed(longPressRunnable, 650);
    }

    private void cancelLongPress() {
        if (longPressRunnable != null) {
            handler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    private void showCloseMenu() {
        if (floatView != null) floatView.setAlpha(1f);
        if (windowManager == null || closeMenuView != null) return;
        float density = getResources().getDisplayMetrics().density;
        TextView menu = new TextView(this);
        menu.setText("关闭悬浮窗");
        menu.setTextColor(Color.rgb(17, 24, 39));
        menu.setTextSize(15);
        menu.setGravity(Gravity.CENTER);
        menu.setPadding((int)(16*density), 0, (int)(16*density), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(245, 255, 255, 255));
        bg.setCornerRadius(18 * density);
        bg.setStroke((int)(1*density), mixColor(readThemeColor(), Color.WHITE, 0.62f));
        menu.setBackground(bg);
        menu.setElevation(12 * density);
        menu.setOnClickListener(v -> closeFloatingWindow());
        closeMenuView = menu;

        closeMenuParams = new WindowManager.LayoutParams();
        closeMenuParams.width = (int) (126 * density);
        closeMenuParams.height = (int) (46 * density);
        closeMenuParams.gravity = Gravity.TOP | Gravity.START;
        closeMenuParams.format = PixelFormat.TRANSLUCENT;
        closeMenuParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) closeMenuParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        else closeMenuParams.type = WindowManager.LayoutParams.TYPE_PHONE;

        int sw = getResources().getDisplayMetrics().widthPixels;
        int menuW = closeMenuParams.width;
        boolean onLeft = params.x < sw / 2;
        closeMenuParams.x = onLeft ? Math.max(6, params.x + bubbleSize - hideOffset + 8) : Math.max(6, params.x - menuW - 8);
        closeMenuParams.y = clampY(params.y + (bubbleSize - closeMenuParams.height) / 2);

        try { windowManager.addView(closeMenuView, closeMenuParams); } catch (Exception ignored) { closeMenuView = null; }
        hideCloseMenuDelayed(3500);
    }

    private void hideCloseMenuDelayed(long delayMs) {
        if (hideMenuRunnable != null) handler.removeCallbacks(hideMenuRunnable);
        hideMenuRunnable = () -> {
            if (windowManager != null && closeMenuView != null) {
                try { windowManager.removeView(closeMenuView); } catch (Exception ignored) {}
                closeMenuView = null;
            }
        };
        handler.postDelayed(hideMenuRunnable, delayMs);
    }

    private void closeFloatingWindow() {
        getSharedPreferences("miaojizhang_native", MODE_PRIVATE)
                .edit()
                .putBoolean("float_enabled", false)
                .putBoolean("float_pending_permission", false)
                .apply();
        Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show();
        stopSelf();
    }

    private void openAddRecord() {
        hideCloseMenuDelayed(0);
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("open_add", true);
        startActivity(intent);
    }

    private void snapToEdge() {
        int sw = getResources().getDisplayMetrics().widthPixels;
        params.x = params.x + bubbleSize / 2 < sw / 2 ? -hideOffset : sw - bubbleSize + hideOffset;
        params.y = clampY(params.y);
        try { windowManager.updateViewLayout(floatView, params); } catch (Exception ignored) {}
        if (floatView != null) {
            handler.removeCallbacks(dimRunnable);
            handler.postDelayed(dimRunnable, 1200);
        }
        getSharedPreferences("miaojizhang_native", MODE_PRIVATE)
                .edit()
                .putInt("float_x", params.x)
                .putInt("float_y", params.y)
                .apply();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelLongPress();
        if (hideMenuRunnable != null) handler.removeCallbacks(hideMenuRunnable);
        if (windowManager != null && closeMenuView != null) {
            try { windowManager.removeView(closeMenuView); } catch (Exception ignored) {}
        }
        closeMenuView = null;
        if (windowManager != null && floatView != null) {
            try { windowManager.removeView(floatView); } catch (Exception ignored) {}
        }
        floatView = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
