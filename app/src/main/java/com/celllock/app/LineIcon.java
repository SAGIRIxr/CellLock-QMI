package com.celllock.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** 线条图标，按 24×24 网格绘制，任意密度下都清晰。 */
final class LineIcon extends View {
    static final String RADIO = "radio", LOCK = "lock", DEVICE = "device", SIM = "sim", ARROW = "arrow", BARS = "bars", BOOKMARK = "bookmark";

    private final String kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int color, level;

    LineIcon(Context context, String kind, int color) {
        super(context);
        this.kind = kind;
        this.color = color;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setColor(int color) {
        this.color = color;
        invalidate();
    }

    /** 信号格数，0–4。 */
    void setLevel(int level) {
        this.level = Math.max(0, Math.min(4, level));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.translate(getPaddingLeft(), getPaddingTop());
        canvas.scale((getWidth() - getPaddingLeft() - getPaddingRight()) / 24f,
                (getHeight() - getPaddingTop() - getPaddingBottom()) / 24f);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.65f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        switch (kind) {
            case BOOKMARK:
                path.rewind();path.moveTo(6,3);path.lineTo(18,3);path.lineTo(18,22);path.lineTo(12,17);path.lineTo(6,22);path.close();canvas.drawPath(path,paint);break;
            case LOCK:
                canvas.drawRoundRect(5, 10, 19, 21, 3, 3, paint);
                canvas.drawArc(8, 2, 16, 16, 180, 180, false, paint);
                canvas.drawLine(12, 14, 12, 17, paint);
                break;
            case DEVICE:
                canvas.drawRoundRect(6, 1.5f, 18, 22.5f, 2.3f, 2.3f, paint);
                canvas.drawLine(10, 4, 14, 4, paint);
                canvas.drawLine(10, 19.5f, 14, 19.5f, paint);
                break;
            case SIM:
                path.rewind();
                path.moveTo(5, 3);
                path.lineTo(14, 3);
                path.lineTo(19, 8);
                path.lineTo(19, 21);
                path.lineTo(5, 21);
                path.close();
                canvas.drawPath(path, paint);
                canvas.drawRoundRect(8, 10, 16, 17, 1, 1, paint);
                canvas.drawLine(12, 10, 12, 17, paint);
                break;
            case ARROW:
                path.rewind();
                path.moveTo(9, 5);
                path.lineTo(16, 12);
                path.lineTo(9, 19);
                canvas.drawPath(path, paint);
                break;
            case BARS:
                paint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 4; i++) {
                    paint.setColor(i < level ? color : 0xffe7ece6);
                    float top = 17 - i * 4;
                    canvas.drawRoundRect(1 + i * 6, top, 5 + i * 6, 24, 1.5f, 1.5f, paint);
                }
                break;
            default: // RADIO：基站
                canvas.drawCircle(12, 12, 2, paint);
                canvas.drawArc(5, 5, 19, 19, 215, 110, false, paint);
                canvas.drawArc(1, 1, 23, 23, 215, 110, false, paint);
                canvas.drawLine(12, 14, 7, 22, paint);
                canvas.drawLine(12, 14, 17, 22, paint);
                canvas.drawLine(9, 19, 15, 19, paint);
        }
        canvas.restore();
    }
}
