package com.google.geminiclone;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GeminiGLSurfaceView extends GLSurfaceView implements GLSurfaceView.Renderer {

    private long lastFrameTimeNs = 0;
    private float animatedTimeSec = 0.0f;
    private float touchX = 0.0f;
    private float touchY = 0.0f;
    private float touchPressure = 0.0f;

    public GeminiGLSurfaceView(Context context) {
        super(context);
        init();
    }

    public GeminiGLSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setEGLContextClientVersion(3);
        setRenderer(this);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        lastFrameTimeNs = 0;
        animatedTimeSec = 0.0f;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int fixedHeightSpec = MeasureSpec.makeMeasureSpec(screenHeight, MeasureSpec.EXACTLY);
        super.onMeasure(widthMeasureSpec, fixedHeightSpec);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        lastFrameTimeNs = 0;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        // Enforce 0.65x hardware buffer scaling: saves 60% fill rate on Adreno GPU
        if (width > 800) {
            int scaledW = (int) (width * 0.65f);
            int scaledH = (int) (height * 0.65f);
            post(() -> getHolder().setFixedSize(scaledW, scaledH));
            return;
        }

        GeminiNative.init(width, height);
        GeminiNative.resize(width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long nowNs = System.nanoTime();
        if (lastFrameTimeNs > 0) {
            float deltaSec = (nowNs - lastFrameTimeNs) / 1_000_000_000.0f;
            // Clamp delta on resume/sleep to prevent sudden animation jumps or stutter
            if (deltaSec > 0.05f) {
                deltaSec = 0.01666f;
            }
            animatedTimeSec += deltaSec;
            // Prevent float precision loss over long running sessions
            if (animatedTimeSec > 628.31853f) {
                animatedTimeSec -= 628.31853f;
            }
        }
        lastFrameTimeNs = nowNs;

        GeminiNative.render(animatedTimeSec, touchX, touchY, touchPressure);

        // Slowly damp touch pressure
        if (touchPressure > 0.01f) {
            touchPressure *= 0.90f;
        } else {
            touchPressure = 0.0f;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        lastFrameTimeNs = 0;
    }

    @Override
    public void onPause() {
        super.onPause();
        lastFrameTimeNs = 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                float scale = (getWidth() > 800) ? 0.65f : 1.0f;
                touchX = event.getX() * scale;
                touchY = (getHeight() - event.getY()) * scale;
                touchPressure = 1.0f;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touchPressure = 0.5f;
                break;
        }
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        GeminiNative.destroy();
    }
}
