package com.carbridge.fakecar;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceView;

/**
 * 保持 CarPlay 屏幕比例（1920×720 = 8:3）的 SurfaceView。
 *
 * <p>宽填满，高按 720/1920 折算，这样视频不拉伸，触摸坐标也能 1:1 对应。
 */
public final class AspectSurfaceView extends SurfaceView {

    private static final float RATIO = 720f / 1920f;

    public AspectSurfaceView(Context context) {
        super(context);
    }

    public AspectSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AspectSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.round(width * RATIO);
        setMeasuredDimension(width, height);
    }
}
