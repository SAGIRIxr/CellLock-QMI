package com.celllock.app;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** 等宽换行的频段芯片，整行铺满可用宽度。 */
final class FlowLayout extends ViewGroup {
    private final int spacing;
    private int columns=1, rowHeight;

    FlowLayout(Context context, int spacingPx) {
        super(context);
        spacing = spacingPx;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int maxWidth = Math.max(0,MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
        int count=0,widest=0;rowHeight=0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            count++;widest=Math.max(widest,child.getMeasuredWidth());
            rowHeight=Math.max(rowHeight,child.getMeasuredHeight());
        }
        columns=Math.max(1,Math.min(count,(maxWidth+spacing)/Math.max(1,widest+spacing)));
        int available=maxWidth-spacing*(columns-1),index=0;
        for(int i=0;i<getChildCount();i++){
            View child=getChildAt(i);if(child.getVisibility()==GONE)continue;
            int column=index++%columns,width=available/columns+(column<available%columns?1:0);
            child.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(rowHeight,MeasureSpec.EXACTLY));
        }
        int rows=(count+columns-1)/columns;
        int height = rows*rowHeight+Math.max(0,rows-1)*spacing + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(height, heightSpec));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int available = Math.max(0,r-l-getPaddingLeft()-getPaddingRight()-spacing*(columns-1));
        int index=0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int column=index%columns,x=column*(available/columns+spacing)+Math.min(column,available%columns);
            int y=index/columns*(rowHeight+spacing),w=child.getMeasuredWidth(),h=child.getMeasuredHeight();index++;
            child.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + w, getPaddingTop() + y + h);
        }
    }
}
