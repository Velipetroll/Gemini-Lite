package com.google.geminiclone;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;

import androidx.appcompat.widget.AppCompatTextView;

public class ShimmerTextView extends AppCompatTextView {

    private LinearGradient gradient;
    private Matrix matrix;
    private float translate = 0f;
    private ValueAnimator animator;

    // Gemini signature shimmer colors
    private final int[] colors = new int[]{
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
        if (w > 0) {
            float gradientWidth = w * 1.5f;
            gradient = new LinearGradient(
                    0, 0, gradientWidth, 0,
                    colors,
                    null,
                    Shader.TileMode.REPEAT
            );
            getPaint().setShader(gradient);

            startShimmerAnimation(gradientWidth);
        }
    }

    private void startShimmerAnimation(float gradientWidth) {
        if (animator != null) {
            animator.cancel();
        }

        animator = ValueAnimator.ofFloat(0, gradientWidth);
        animator.setDuration(3500);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            translate = (float) animation.getAnimatedValue();
            if (matrix != null && gradient != null) {
                matrix.setTranslate(translate, 0);
                gradient.setLocalMatrix(matrix);
                invalidate();
            }
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
        }
    }
}
