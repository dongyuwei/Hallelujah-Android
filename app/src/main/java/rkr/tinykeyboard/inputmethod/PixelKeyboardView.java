package rkr.tinykeyboard.inputmethod;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.util.AttributeSet;
import android.view.MotionEvent;

import java.util.List;

/**
 * KeyboardView that renders keys in fcitx5-android's Pixel Dark look:
 * borderless 4dp-rounded keys in four flavors (normal / alt / accent /
 * space, see key_bg_*.xml), Material icons on modifier keys, and the input
 * language labeled on the space bar.
 */
public class PixelKeyboardView extends KeyboardView {

    private final Drawable bgNormal;
    private final Drawable bgAlt;
    private final Drawable bgAccent;
    private final Drawable bgSpace;
    private final Drawable iconShiftLocked;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** the key currently under the finger, for the pressed highlight */
    private Keyboard.Key pressedKey;

    public PixelKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        bgNormal = getResources().getDrawable(R.drawable.key_bg_normal, null);
        bgAlt = getResources().getDrawable(R.drawable.key_bg_alt, null);
        bgAccent = getResources().getDrawable(R.drawable.key_bg_accent, null);
        bgSpace = getResources().getDrawable(R.drawable.key_bg_space, null);
        iconShiftLocked = getResources().getDrawable(R.drawable.ic_key_shift_locked, null);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT);
        setPreviewEnabled(false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent me) {
        int action = me.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            Keyboard.Key old = pressedKey;
            pressedKey = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    ? null : keyAt(me.getX(), me.getY());
            if (old != pressedKey) {
                invalidateAllKeys();
            }
        }
        return super.onTouchEvent(me);
    }

    private Keyboard.Key keyAt(float x, float y) {
        Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return null;
        }
        // framework touches arrive in view coordinates; key boxes are laid out
        // inside the padded drawing area
        int px = (int) x - getPaddingLeft();
        int py = (int) y - getPaddingTop();
        List<Keyboard.Key> keys = keyboard.getKeys();
        for (Keyboard.Key key : keys) {
            if (key.isInside(px, py)) {
                return key;
            }
        }
        return null;
    }

    @Override
    public void onDraw(Canvas canvas) {
        Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return;
        }
        List<Keyboard.Key> keys = keyboard.getKeys();
        int bottomRowY = 0;
        for (Keyboard.Key key : keys) {
            bottomRowY = Math.max(bottomRowY, key.y);
        }
        boolean shifted = keyboard.isShifted();
        for (Keyboard.Key key : keys) {
            Drawable bg = backgroundFor(key, bottomRowY);
            if (pressedKey == key) {
                bg.setState(new int[]{android.R.attr.state_pressed});
            } else if (key.codes[0] == Keyboard.KEYCODE_SHIFT && shifted) {
                bg.setState(new int[]{android.R.attr.state_checked});
            } else {
                bg.setState(new int[0]);
            }
            bg.setBounds(key.x, key.y, key.x + key.width, key.y + key.height);
            bg.draw(canvas);
            if (key.icon != null && key.label == null) {
                drawIcon(canvas, key, key.icon);
            } else if (key.label != null && !key.label.toString().trim().isEmpty()) {
                drawLabel(canvas, key, shifted);
            }
        }
    }

    private Drawable backgroundFor(Keyboard.Key key, int bottomRowY) {
        int code = key.codes[0];
        if (code == Keyboard.KEYCODE_DONE) {
            return bgAccent;
        }
        if (code == 32) {
            return bgSpace;
        }
        if (code == Keyboard.KEYCODE_SHIFT || code == Keyboard.KEYCODE_DELETE
                || code == Keyboard.KEYCODE_MODE_CHANGE
                || code == LatinKeyboard.KEYCODE_LANGUAGE_SWITCH
                || (key.y >= bottomRowY && (code == 44 || code == 46 || code == 8230))) {
            return bgAlt;
        }
        return bgNormal;
    }

    private void drawLabel(Canvas canvas, Keyboard.Key key, boolean shifted) {
        CharSequence label = key.label;
        int code = key.codes[0];
        if (code == 32) {
            // language indicator on the space bar, like fcitx5-android
            paint.setTextSize(dp(13));
            paint.setColor(0xffd6d6d6);
        } else if (code == Keyboard.KEYCODE_DONE) {
            paint.setTextSize(dp(15));
            paint.setColor(0xffffffff);
        } else {
            paint.setTextSize(getResources().getDimensionPixelSize(R.dimen.key_text_size));
            paint.setColor(0xfffafafa);
            if (shifted && label.length() == 1 && Character.isLowerCase(label.charAt(0))) {
                label = String.valueOf(Character.toUpperCase(label.charAt(0)));
            }
        }
        float centerX = key.x + key.width / 2f;
        float centerY = key.y + key.height / 2f - (paint.ascent() + paint.descent()) / 2f;
        canvas.drawText(label, 0, label.length(), centerX, centerY, paint);
    }

    private void drawIcon(Canvas canvas, Keyboard.Key key, Drawable icon) {
        Drawable toDraw = icon;
        if (key.codes[0] == Keyboard.KEYCODE_SHIFT && getKeyboard().isShifted()) {
            toDraw = iconShiftLocked;
        }
        int size = dp(28);
        int iconSize = Math.min(size, key.height - dp(14));
        int left = key.x + (key.width - iconSize) / 2;
        int top = key.y + (key.height - iconSize) / 2;
        toDraw.setBounds(left, top, left + iconSize, top + iconSize);
        toDraw.draw(canvas);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
