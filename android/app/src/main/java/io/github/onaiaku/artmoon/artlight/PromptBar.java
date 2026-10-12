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
    private final Map<String, int[]> origKeycapPad = new LinkedHashMap<>();
    private final Map<String, Integer> origKeycapGravity = new LinkedHashMap<>();
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
            // A glyph replaces the keycap's TEXT, and the resting chip is sized by
            // that text plus its padding. Saving the background alone would put a
            // glyph back on a chip that still had no room around the letter.
            origKeycapPad.put(action, new int[] {
                    keycap.getPaddingLeft(), keycap.getPaddingTop(),
                    keycap.getPaddingRight(), keycap.getPaddingBottom() });
            origKeycapGravity.put(action, keycap.getGravity());
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
                if (gamepad) {
                    // The real vendor glyph: circle and symbol in one image, the
                    // same art the desktop draws. It carries its own letter, so the
                    // keycap shows the picture and no text at all.
                    int glyph = PadGlyphs.drawableFor(
                            InputModeManager.get().getGlyphSet(), buttonKeyFor(p.action));
                    android.graphics.drawable.Drawable d = glyph != 0
                            ? PadGlyphs.sized(activity.getResources(), glyph) : null;
                    if (d != null) {
                        p.keycap.setText("");
                        p.keycap.setCompoundDrawables(d, null, null, null);
                        p.keycap.setGravity(android.view.Gravity.CENTER);
                        p.keycap.setPadding(0, 0, 0, 0);
                        p.keycap.setBackground(null);
                    } else {
                        // No glyph for this button: show the key letter rather than
                        // leaving the prompt blank.
                        restoreKeycap(p);
                        p.keycap.setText(textFor(mode, p.action));
                    }
                } else { // keyboard
                    restoreKeycap(p);
                    p.keycap.setText(textFor(mode, p.action));
                }
            }
        }
    }

    /** Undo everything gamepad mode did to a keycap, so text mode is untouched. */
    private void restoreKeycap(Pill p) {
        p.keycap.setCompoundDrawables(null, null, null, null);
        android.graphics.drawable.Drawable bg = origKeycapBg.get(p.action);
        if (bg != null) {
            p.keycap.setBackground(bg);
        }
        Integer col = origKeycapColor.get(p.action);
        if (col != null) {
            p.keycap.setTextColor(col);
        }
        int[] pad = origKeycapPad.get(p.action);
        if (pad != null) {
            p.keycap.setPadding(pad[0], pad[1], pad[2], pad[3]);
        }
        Integer grav = origKeycapGravity.get(p.action);
        if (grav != null) {
            p.keycap.setGravity(grav);
        }
    }

    /**
     * Gamepad mode draws a glyph, not text, so this is only reached when no glyph
     * exists for the button — the key letter is the honest thing to show then.
     */
    private static String textFor(InputModeManager.Mode mode, String action) {
        return keyboardKey(action);
    }

    /**
     * The pad button a prompt names, from the desktop's own hint row
     * (app/gui/AppShell.qml, _hintsHome): Power is X, Settings is Y, Exit is B.
     * Anything unlisted selects with A.
     *
     * Android used to invent a "power paddle" here — a flat grey circle with an
     * arrow typed on it — where the desktop has always put X. The keyboard
     * letters (P / S / Esc) were already right, which is why they are untouched.
     */
    private static String buttonKeyFor(String action) {
        if ("settings".equals(action)) {
            return "Y";
        }
        if ("shutdown".equals(action)) {
            return "X";
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
