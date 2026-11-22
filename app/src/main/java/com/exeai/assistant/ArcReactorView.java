package com.exeai.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Red holographic JARVIS-style orb: a glowing sphere with thin rotating rings, tick marks and
 * orbiting dots. Drawn with Canvas. Used by the secret silent mode (5087).
 */
public class ArcReactorView extends View {
    private static final int RED = Color.rgb(255, 45, 55);
    private static final int RED_DIM = Color.argb(120, 255, 45, 55);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float angle = 0f;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            angle = (angle + 1.5f) % 360f;
            invalidate();
            postDelayed(this, 16);
        }
    };

    public ArcReactorView(Context context) {
        super(context);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) * 0.95f;
        if (r <= 0) return;

        // Glowing sphere: bright red edge fading to black in the middle.
        RadialGradient sphere = new RadialGradient(cx, cy, r,
                new int[]{0x00000000, 0x11FF2D37, 0x55FF2D37, 0xAAFF2D37},
                new float[]{0.0f, 0.55f, 0.85f, 1f}, Shader.TileMode.CLAMP);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(sphere);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setShader(null);

        // Thin outer ring with tick marks that rotate slowly.
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(RED);
        paint.setStrokeWidth(Math.max(2f, r * 0.012f));
        canvas.drawCircle(cx, cy, r * 0.96f, paint);

        paint.setStrokeWidth(Math.max(2f, r * 0.02f));
        for (int i = 0; i < 60; i++) {
            double a = Math.toRadians(i * 6 + angle);
            float inner = (i % 5 == 0) ? r * 0.86f : r * 0.9f;
            float x1 = cx + (float) Math.cos(a) * inner;
            float y1 = cy + (float) Math.sin(a) * inner;
            float x2 = cx + (float) Math.cos(a) * r * 0.94f;
            float y2 = cy + (float) Math.sin(a) * r * 0.94f;
            canvas.drawLine(x1, y1, x2, y2, paint);
        }

        // Dashed rings rotating in alternating directions.
        paint.setStrokeWidth(Math.max(2f, r * 0.025f));
        for (int i = 0; i < 3; i++) {
            float radius = r * (0.7f - 0.14f * i);
            float direction = (i % 2 == 0) ? 1f : -1f;
            paint.setColor(i == 0 ? RED : RED_DIM);
            paint.setPathEffect(new DashPathEffect(new float[]{r * 0.08f, r * 0.05f}, 0));
            box.set(cx - radius, cy - radius, cx + radius, cy + radius);
            canvas.save();
            canvas.rotate(angle * direction * (1f + i * 0.5f), cx, cy);
            canvas.drawOval(box, paint);
            canvas.restore();
        }
        paint.setPathEffect(null);

        // Solid arc segment on the middle ring.
        paint.setColor(RED);
        paint.setStrokeWidth(Math.max(3f, r * 0.035f));
        float midRadius = r * 0.56f;
        box.set(cx - midRadius, cy - midRadius, cx + midRadius, cy + midRadius);
        canvas.drawArc(box, angle * 2f, 90f, false, paint);
        canvas.drawArc(box, angle * 2f + 180f, 50f, false, paint);

        // Three dots orbiting on an outer path.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(RED);
        float orbit = r * 0.8f;
        for (int i = 0; i < 3; i++) {
            double a = Math.toRadians(angle * -1.2f + i * 120);
            canvas.drawCircle(cx + (float) Math.cos(a) * orbit, cy + (float) Math.sin(a) * orbit,
                    Math.max(3f, r * 0.03f), paint);
        }

        // Thin bright core.
        float pulse = 0.5f + 0.5f * (float) Math.sin(angle * Math.PI / 45.0);
        paint.setColor(RED_DIM);
        canvas.drawCircle(cx, cy, r * (0.12f + 0.02f * pulse), paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, r * 0.03f, paint);
    }
}
