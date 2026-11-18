package com.carbridge.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 双向数据流指示器（完全静态，无动画）。
 */
public final class LinkDiagramView extends View {

    public enum Phase {IDLE, ACTIVE, DONE, ERROR}

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int cIdle;
    private final int cActive;
    private final int cOk;
    private final int cErr;

    private Phase phase = Phase.IDLE;

    public LinkDiagramView(Context context, AttributeSet attrs) {
        super(context, attrs);
        cIdle = context.getResources().getColor(R.color.state_idle, context.getTheme());
        // 深色 tile 上进行中用亮蓝 #2997FF（深底上 #0066CC 几乎看不见）
        cActive = context.getResources().getColor(R.color.tile_blue, context.getTheme());
        cOk = context.getResources().getColor(R.color.state_ok, context.getTheme());
        cErr = context.getResources().getColor(R.color.state_err, context.getTheme());

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        markPaint.setStyle(Paint.Style.STROKE);
        markPaint.setStrokeWidth(dp(2f));
        markPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setPhase(Phase p) {
        if (p == phase) return;
        phase = p;
        invalidate();
    }

    public Phase phase() {
        return phase;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float mid = h / 2f;
        float xL = dp(8f);
        float xR = w - dp(8f);

        int color = phaseColor();
        boolean idle = phase == Phase.IDLE;

        // 连接线
        linePaint.setColor(color);
        linePaint.setAlpha(idle ? 55 : (phase == Phase.ERROR ? 110 : 170));
        canvas.drawLine(xL, mid, xR, mid, linePaint);

        // 两端圆点
        dotPaint.setColor(color);
        dotPaint.setAlpha(idle ? 110 : 230);
        canvas.drawCircle(xL, mid, dp(4.5f), dotPaint);
        canvas.drawCircle(xR, mid, dp(4.5f), dotPaint);

        if (phase == Phase.ERROR) {
            drawErrorMark(canvas, (xL + xR) / 2f, mid);
        }
    }

    private void drawErrorMark(Canvas canvas, float cx, float cy) {
        markPaint.setColor(cErr);
        float s = dp(5f);
        canvas.drawLine(cx - s, cy - s, cx + s, cy + s, markPaint);
        canvas.drawLine(cx + s, cy - s, cx - s, cy + s, markPaint);
    }

    private int phaseColor() {
        switch (phase) {
            case ACTIVE:
                return cActive;
            case DONE:
                return cOk;
            case ERROR:
                return cErr;
            default:
                return cIdle;
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
