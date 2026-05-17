package com.example.parsecdemo;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Helpers for constructing Material 3 widgets programmatically without
 * dragging in XML layouts. Reads tokens from the current theme so all
 * widgets stay in lock-step with the theme palette.
 */
final class MaterialUi {
    private MaterialUi() {}

    /** Resolve a theme attribute (e.g. R.attr.colorPrimary) to an ARGB color. */
    static int color(Context ctx, int attrRes) {
        TypedValue tv = new TypedValue();
        if (ctx.getTheme().resolveAttribute(attrRes, tv, true)) {
            if (tv.resourceId != 0) return ctx.getResources().getColor(tv.resourceId);
            return tv.data;
        }
        return 0;
    }

    /** Solid-fill rounded surface drawable using the theme's surfaceContainer color. */
    static Drawable surfaceContainer(Context ctx, int cornerPx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color(ctx, com.google.android.material.R.attr.colorSurfaceContainer));
        g.setCornerRadius(cornerPx);
        return g;
    }

    /** Solid-fill rounded surface drawable using the theme's surfaceContainerHigh color. */
    static Drawable surfaceContainerHigh(Context ctx, int cornerPx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color(ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh));
        g.setCornerRadius(cornerPx);
        return g;
    }

    /**
     * Build a Material 3 outlined text field around the supplied edit text.
     * The edit text becomes a child of the returned TextInputLayout.
     */
    static TextInputLayout textField(Context ctx, String hint, TextInputEditText edit, int inputType) {
        TextInputLayout layout = new TextInputLayout(
                new androidx.appcompat.view.ContextThemeWrapper(ctx,
                        com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox));
        layout.setHint(hint);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        int outline = color(ctx, com.google.android.material.R.attr.colorOutline);
        int primary = color(ctx, com.google.android.material.R.attr.colorPrimary);
        layout.setBoxStrokeColor(primary);
        layout.setHintTextColor(ColorStateList.valueOf(color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant)));
        layout.setDefaultHintTextColor(ColorStateList.valueOf(color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant)));

        edit.setInputType(inputType);
        edit.setTextColor(color(ctx, com.google.android.material.R.attr.colorOnSurface));
        layout.addView(edit);
        return layout;
    }
}
