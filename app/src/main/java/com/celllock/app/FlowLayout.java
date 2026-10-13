package com.celllock.app;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** 自动换行的横向排列，用于频段芯片。 */
final class FlowLayout extends ViewGroup {
    private final int spacing;

    FlowLayout(Context context, int spacingPx) {
        super(context);
        spacing = spacingPx;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int maxWidth = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, lineHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            if (x > 0 && x + child.getMeasuredWidth() > maxWidth) {
                x = 0;
                y += lineHeight + spacing;
                lineHeight = 0;
            }
            x += child.getMeasuredWidth() + spacing;
            lineHeight = Math.max(lineHeight, child.getMeasuredHeight());
        }
        int height = y + lineHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(height, heightSpec));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxWidth = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, lineHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
            if (x > 0 && x + w > maxWidth) {
                x = 0;
                y += lineHeight + spacing;
                lineHeight = 0;
            }
            child.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + w, getPaddingTop() + y + h);
            x += w + spacing;
            lineHeight = Math.max(lineHeight, h);
        }
    }
}
