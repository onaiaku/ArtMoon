package io.github.onaiaku.artmoon.artlight;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PromptBar — footer prompt pills that always show KEY + NAME, with the key
 * half rendered per input mode (Nik's spec, desktop parity):
 *
 *   TOUCH     → name only ("Shutdown"), keycap hidden — the pill IS the button
 *   KEYBOARD  → [P] Shutdown   (flat keycap chip with the key letter)
 *   GAMEPAD   → (Y) Settings   (coloured Xbox face button + name)
 *
 * Each screen registers (keycapView, labelView, action) triples. The keycap
 * is shown/hidden and re-skinned on mode change; the label always keeps its
 * real name — labels are never overwritten.
 */
public final class PromptBar {

    /** One prompt: the keycap chip and the label next to it. */
    public static class Pill {
        public final TextView keycap;
        public final TextView label;
        /** Semantic action: "settings" | "shutdown" | "exit" | "hosts" | "back" */
        public final String action;

        public Pill(TextView keycap, TextView label, String action) {
            this.keycap = keycap;
            this.label = label;
            this.action = action;
        }
    }

    private final Map<String, Pill> pills = new LinkedHashMap<>();
    // Resting state per action, restored when leaving gamepad mode
    private final Map<String, android.graphics.drawable.Drawable> origKeycapBg = new LinkedHashMap<>();
    private final Map<String, Integer> origKeycapColor = new LinkedHashMap<>();
    private final Activity activity;
    private boolean registered = false;

    public PromptBar(Activity activity) {
        this.activity = activity;
        InputModeManager.get().addListener(new InputModeManager.PromptRefreshListener() {
            @Override
            public void onInputModeChanged(InputModeManager.Mode newMode) {
                refresh(newMode);
            }
        });
    }

    /** Register a keycap+label pair. Either view may be null (null-safe). */
    public void register(TextView keycap, TextView label, String action) {
        if (keycap == null && label == null) {
            return;
        }
        if (keycap != null && !origKeycapBg.containsKey(action)) {
            origKeycapBg.put(action, keycap.getBackground());
            origKeycapColor.put(action, keycap.getCurrentTextColor());
        }
        pills.put(action, new Pill(keycap, label, action));
    }

    /** Register by view id lookup (null-safe — layouts may lack pills). */
    public void registerById(int keycapId, int labelId, String action) {
        View k = activity.findViewById(keycapId);
        View l = activity.findViewById(labelId);
        register(k instanceof TextView ? (TextView) k : null,
                 l instanceof TextView ? (TextView) l : null,
                 action);
    }

    /** First paint + mark as live. Call after all registrations. */
    public void attach() {
        registered = true;
        refresh(InputModeManager.get().getMode());
    }

    public boolean isAttached() {
        return registered;
    }

    private void refresh(InputModeManager.Mode mode) {
        if (!registered) {
            return;
        }
        for (Pill p : pills.values()) {
            boolean gamepad = mode == InputModeManager.Mode.GAMEPAD;
            boolean keyboard = mode == InputModeManager.Mode.KEYBOARD;

            if (p.label != null) {
                // Label always shows the real name — restore the string
                // resource in case anything ever overwrote it.
                p.label.setText(labelFor(p.action));
            }

            if (p.keycap == null) {
                continue;
            }
            if (mode == InputModeManager.Mode.TOUCH) {
                // Touch: the pill IS the button — no fake keycap.
                p.keycap.setVisibility(View.GONE);
            } else {
                p.keycap.setVisibility(View.VISIBLE);
                p.keycap.setText(textFor(mode, p.action));
                if (gamepad) {
                    // Coloured Xbox face button + dark lettering
                    p.keycap.setBackgroundResource(gamepadBg(p.action));
                    p.keycap.setTextColor(0xFF1A1D22);
                } else { // keyboard
                    android.graphics.drawable.Drawable bg = origKeycapBg.get(p.action);
                    if (bg != null) {
                        p.keycap.setBackground(bg);
                    }
                    Integer col = origKeycapColor.get(p.action);
                    if (col != null) {
                        p.keycap.setTextColor(col);
                    }
                }
            }
        }
    }

    private static String textFor(InputModeManager.Mode mode, String action) {
        if (mode == InputModeManager.Mode.GAMEPAD) {
            return gamepadGlyph(action);
        }
        return keyboardKey(action);
    }

    private static int gamepadBg(String action) {
        if ("settings".equals(action)) {
            return io.github.onaiaku.artmoon.R.drawable.am_pad_y; // yellow Y
        }
        if ("shutdown".equals(action)) {
            return io.github.onaiaku.artmoon.R.drawable.am_pad_power;
        }
        // exit / back / hosts → red B
        return io.github.onaiaku.artmoon.R.drawable.am_pad_b;
    }

    private static String gamepadGlyph(String action) {
        // Desktop mapping: Y = settings, B = back/exit, A = select,
        // power gets the neutral pad.
        if ("settings".equals(action)) {
            return "Y";
        }
        if ("shutdown".equals(action)) {
            return "⟳";
        }
        if ("exit".equals(action) || "back".equals(action) || "hosts".equals(action)) {
            return "B";
        }
        return "A";
    }

    private static String keyboardKey(String action) {
        if ("settings".equals(action)) {
            return "S";
        }
        if ("shutdown".equals(action)) {
            return "P";
        }
        if ("exit".equals(action) || "back".equals(action) || "hosts".equals(action)) {
            return "Esc";
        }
        return "Enter";
    }

    private static String labelFor(String action) {
        if ("shutdown".equals(action)) {
            return "Shutdown";
        }
        if ("settings".equals(action)) {
            return "Settings";
        }
        if ("hosts".equals(action)) {
            return "Hosts";
        }
        if ("exit".equals(action) || "back".equals(action)) {
            return "Exit";
        }
        return action;
    }
}
