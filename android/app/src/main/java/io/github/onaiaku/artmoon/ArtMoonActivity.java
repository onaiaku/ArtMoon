package io.github.onaiaku.artmoon;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import io.github.onaiaku.artmoon.artlight.InputModeManager;

/**
 * ArtMoonActivity — shared base for the shell screens (hosts, picker).
 *
 * Adds, app-wide:
 *  - Input-mode tracking (touch / gamepad / keyboard) via the dispatch hooks,
 *    so every PromptBar re-renders when the user switches devices.
 *  - GAMEPAD-mode focus bloom: rows and footer pills get the focus drawable
 *    applied when they take focus, so D-pad navigation is visible.
 *  - Back button behaves as "B" everywhere (no special-casing per screen).
 *
 * Keyboard users already get Android's default D-pad traversal; this class
 * only layers the ArtMoon affordances on top.
 */
public abstract class ArtMoonActivity extends Activity {

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        InputModeManager.get().notifyTouchEvent();
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        InputModeManager.get().notifyKeyEvent(event);
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onContentChanged() {
        super.onContentChanged();
        applyFocusBloom(getWindow().getDecorView());
    }

    /**
     * Recursively enable the focus bloom drawable on focusable rows/buttons
     * that don't already declare a focus state list. Idempotent; safe to call
     * after grid content changes too (adapter getView calls applyBloom).
     */
    public static void applyFocusBloom(View root) {
        if (root == null) {
            return;
        }
        if (root.isFocusable() && root.getBackground() != null
                && !(root instanceof TextView)) {
            root.setOnFocusChangeListener(BLOOM);
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyFocusBloom(vg.getChildAt(i));
            }
        }
    }

    private static final View.OnFocusChangeListener BLOOM =
            new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    v.setScaleX(hasFocus ? 1.03f : 1.0f);
                    v.setScaleY(hasFocus ? 1.03f : 1.0f);
                    v.setAlpha(hasFocus ? 1.0f : 0.92f);
                }
            };
}
