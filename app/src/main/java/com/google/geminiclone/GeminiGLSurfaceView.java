package com.google.geminiclone;

import android.content.Context;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GeminiGLSurfaceView extends GLSurfaceView implements GLSurfaceView.Renderer {

    private long lastFrameTimeNs = 0;
    private float animatedTimeSec = 0.0f;
    private volatile float touchX = 0.0f;
    private volatile float touchY = 0.0f;
    private volatile float touchPressure = 0.0f;
    private int surfaceWidth = 0;
    private int surfaceHeight = 0;
    private boolean contextCreated = false;

    public GeminiGLSurfaceView(Context context) {
        super(context);
        init();
    }

    public GeminiGLSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setEGLContextClientVersion(2);
        // Official RGB888 config with needDepth=false (0 depth, 0 stencil): eliminates depth buffer VRAM & tile bandwidth safely on all GPUs
        setEGLConfigChooser(false);
        setPreserveEGLContextOnPause(true);
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
        contextCreated = true;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        // Enforce 0.65x hardware buffer scaling: saves 58% fill rate while hardware bilinear filter keeps nebula/star crisp
        if (width > 800) {
            int scaledW = (int) (width * 0.65f);
            int scaledH = (int) (height * 0.65f);
            post(() -> getHolder().setFixedSize(scaledW, scaledH));
            return;
        }

        surfaceWidth = width;
        surfaceHeight = height;
        if (contextCreated) {
            GeminiNative.init(width, height);
            contextCreated = false;
        } else {
            GeminiNative.resize(width, height);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long nowNs = System.nanoTime();
        if (lastFrameTimeNs > 0) {
            float deltaSec = (nowNs - lastFrameTimeNs) * 1e-9f;
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
                int vw = getWidth();
                int vh = getHeight();
                if (vw > 0 && vh > 0 && surfaceWidth > 0 && surfaceHeight > 0) {
                    touchX = event.getX() * ((float) surfaceWidth / vw);
                    touchY = (vh - event.getY()) * ((float) surfaceHeight / vh);
                } else {
                    float scale = (vw > 800) ? 0.65f : 1.0f;
                    touchX = event.getX() * scale;
                    touchY = (vh - event.getY()) * scale;
                }
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
