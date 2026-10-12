package io.github.onaiaku.artmoon.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.TextView;

import io.github.onaiaku.artmoon.R;

/**
 * A stage action button's body — the ones in the action row and the panel pair under the cover.
 *
 * <p>HostStage.qml:1444-1477 and 1727-1764, which are deliberately the same body used twice:
 *
 * <pre>
 *   height: _px(58)   radius: _px(10)   width: label + _px(54)   _px(12) between them
 *   colour:       _lit ? (danger ? Theme.danger : Theme.accent) : "#14ffffff"
 *   border.width: _focused ? 2 : 1
 *   border.color: _lit ? (danger ? Theme.danger : Theme.accent) : Theme.lineHigh
 *   label colour: _lit ? (danger ? "#1a0505" : Theme.onAccent) : (danger ? danger : Theme.text)
 * </pre>
 *
 * <p>⚠️ Two things were wrong with the drawables these replaced, and both are why this is code:
 *
 * <ol>
 *   <li>They were {@code <ripple><selector><shape/></selector></ripple>}. AmScale scales the
 *       corner radius of a GradientDrawable it finds as a view's background, and a drawable
 *       inside a selector inside a ripple is none of those things — so the corners never came
 *       down with the button. The button is _px(58) tall; the corners stayed a flat 28dp. On a
 *       button that has shrunk to ~41dp that is a radius past half the height, which is what
 *       draws the two end caps through each other and slices the pill across the middle.</li>
 *   <li>They painted the two buttons differently at rest — accent fill on one, soft accent on
 *       the other. The desktop paints EVERY action button the same at rest (#14ffffff with a
 *       1px lineHigh border) and gives a button its own colour only while it is the one being
 *       pointed at. The row is meant to be one quiet line of buttons with a light travelling
 *       along it, not one pre-selected primary and a secondary next to it.</li>
 * </ol>
 */
public final class AmActionPill {

    /** HostStage.qml:1458 — {@code radius: stage._px(10)}. Not a full-round pill. */
    private static final float RADIUS = 10f;

    private AmActionPill() {
    }

    public static void apply(TextView view, AmScale s, boolean danger) {
        if (view == null || s == null) {
            return;
        }
        Context c = view.getContext();
        int fill = c.getResources().getColor(R.color.am_accent);
        int onFill = c.getResources().getColor(R.color.am_on_accent);
        if (danger) {
            fill = c.getResources().getColor(R.color.am_danger);
            onFill = c.getResources().getColor(R.color.am_ground);
        }
        int restFill = 0x14FFFFFF;                                  // "#14ffffff"
        int restLine = c.getResources().getColor(R.color.am_line_high);
        int restText = c.getResources().getColor(R.color.am_text);
        if (danger) {
            restText = c.getResources().getColor(R.color.am_danger);
        }

        float radius = AmScale.cornerPx(RADIUS, s);
        int hairline = Math.max(1, Math.round(s.uiScale()));
        int lit = Math.max(2, Math.round(2 * s.uiScale()));          // _focused ? 2 : 1

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { android.R.attr.state_activated },
                shape(fill, radius, lit, fill));
        states.addState(new int[] { android.R.attr.state_focused },
                shape(fill, radius, lit, fill));
        states.addState(new int[] { android.R.attr.state_pressed },
                shape(fill, radius, lit, fill));
        states.addState(new int[] {}, shape(restFill, radius, hairline, restLine));
        view.setBackground(states);

        // The label follows the fill, or lit text on a lit button stops being readable.
        view.setTextColor(new ColorStateList(
                new int[][] {
                        new int[] { android.R.attr.state_activated },
                        new int[] { android.R.attr.state_focused },
                        new int[] { android.R.attr.state_pressed },
                        new int[] {},
                },
                new int[] { onFill, onFill, onFill, restText }));
    }

    private static GradientDrawable shape(int fill, float radius, int stroke, int strokeColour) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (stroke > 0) {
            g.setStroke(stroke, strokeColour);
        }
        return g;
    }

    /** For anything that wants the rest fill without a state list. */
    public static int restFill() {
        return 0x14FFFFFF;
    }
}
