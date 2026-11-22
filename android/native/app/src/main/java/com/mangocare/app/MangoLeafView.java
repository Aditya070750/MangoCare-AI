package com.mangocare.app;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

final class MangoLeafView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path blade = new Path();
    private final Path veins = new Path();
    private final Shader green = new LinearGradient(26, 18, 80, 79,
            new int[] {0xffb4db6f, 0xff518839, 0xff155b38}, null, Shader.TileMode.CLAMP);
    private ObjectAnimator sway;

    MangoLeafView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setCameraDistance(900 * getResources().getDisplayMetrics().density);
        blade.moveTo(26, 80);
        blade.cubicTo(13, 63, 29, 28, 82, 12);
        blade.cubicTo(74, 29, 72, 46, 59, 62);
        blade.cubicTo(49, 75, 38, 82, 26, 80);
        blade.close();

        veins.moveTo(21, 88);
        veins.quadTo(45, 55, 79, 16);
        for (int i = 0; i < 6; i++) {
            float x = 32 + 5.6f * i;
            float y = 73 - 7f * i;
            veins.moveTo(x, y);
            veins.quadTo(x - 5, y - 9, x - 4, y - 19);
            veins.moveTo(x, y);
            veins.quadTo(x + 10, y + 0.5f, x + 17, y - 6);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(getWidth() / 100f, getHeight() / 100f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xffdbeac1);
        canvas.drawCircle(50, 50, 44, paint);
        paint.setColor(0x39224b32);
        canvas.save();
        canvas.translate(3, 4);
        canvas.drawPath(blade, paint);
        canvas.restore();
        paint.setShader(green);
        canvas.drawPath(blade, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.1f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xaaddeda1);
        canvas.drawPath(veins, paint);
        canvas.restore();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        sway = ObjectAnimator.ofPropertyValuesHolder(this,
                PropertyValuesHolder.ofFloat("rotationY", -26f, 13f, 0f),
                PropertyValuesHolder.ofFloat("rotationX", 10f, -5f, 0f));
        sway.setDuration(840);
        sway.setInterpolator(new DecelerateInterpolator());
        sway.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (sway != null) sway.cancel();
        super.onDetachedFromWindow();
    }
}
