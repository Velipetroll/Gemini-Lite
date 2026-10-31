package com.google.geminiclone;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatTextView;

public class ShimmerTextView extends AppCompatTextView {

    private LinearGradient gradient;
    private Matrix matrix;
    private float translate = 0f;
    private float gradientWidth = 0f;
    private ValueAnimator animator;

    // Gemini signature shimmer colors
    private static final int[] COLORS = new int[]{
            Color.parseColor("#4285F4"), // Electric Blue
            Color.parseColor("#9B51E0"), // Deep Purple
            Color.parseColor("#FF6B8B"), // Rose / Coral
            Color.parseColor("#59C0F0"), // Light Cyan
            Color.parseColor("#4285F4")  // Loop back to blue
    };

    public ShimmerTextView(Context context) {
        super(context);
        init();
    }

    public ShimmerTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ShimmerTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        matrix = new Matrix();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && w != oldw) {
            gradientWidth = w * 1.5f;
            gradient = new LinearGradient(
                    0, 0, gradientWidth, 0,
                    COLORS,
                    null,
                    Shader.TileMode.REPEAT
            );
            getPaint().setShader(gradient);
            updateAnimationState();
        }
    }

    private void ensureAnimator() {
        if (animator != null || gradientWidth <= 0f) return;

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(3500);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            if (!isShown() || matrix == null || gradient == null) return;
            // getAnimatedFraction() returns primitive float directly (zero Float autoboxing per frame)
            float newTranslate = animation.getAnimatedFraction() * gradientWidth;
            if (Math.abs(newTranslate - translate) >= 0.5f) {
                translate = newTranslate;
                matrix.setTranslate(translate, 0f);
                gradient.setLocalMatrix(matrix);
                invalidate();
            }
        });
    }

    private void updateAnimationState() {
        if (isAttachedToWindow() && isShown() && getWindowVisibility() == View.VISIBLE && gradientWidth > 0f) {
            ensureAnimator();
            if (animator != null && !animator.isRunning()) {
                animator.start();
            }
        } else if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateAnimationState();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateAnimationState();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimationState();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
        }
    }
}
