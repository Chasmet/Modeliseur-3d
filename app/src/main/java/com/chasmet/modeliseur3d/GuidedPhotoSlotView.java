package com.chasmet.modeliseur3d;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** Case visuelle superposant la photo détourée au gabarit de silhouette attendu. */
public final class GuidedPhotoSlotView extends FrameLayout {
    private static final int COLOR_VALID = Color.rgb(56, 176, 95);
    private static final int COLOR_INVALID = Color.rgb(220, 74, 74);
    private static final int COLOR_WAITING = Color.rgb(255, 151, 45);

    private final int viewIndex;
    private final ImageView preview;
    private final TextView title;
    private final TextView status;
    private final GuideSilhouetteView guide;

    private boolean accepted;
    private int lastScore;
    private String lastMessage;

    public GuidedPhotoSlotView(Context context, int viewIndex) {
        super(context);
        this.viewIndex = viewIndex;
        setClickable(true);
        setFocusable(true);
        setPadding(dp(6), dp(6), dp(6), dp(6));
        setBackground(createBackground(COLOR_WAITING));

        preview = new ImageView(context);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setAdjustViewBounds(false);
        preview.setAlpha(0.74f);
        addView(preview, new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
        ));

        guide = new GuideSilhouetteView(context, viewIndex);
        addView(guide, new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
        ));

        title = new TextView(context);
        title.setText(ManualViewPlan.getSlotLabel(viewIndex));
        title.setTextColor(Color.WHITE);
        title.setTextSize(12.5f);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(5), dp(3), dp(5), dp(3));
        title.setBackgroundColor(0xB5181C24);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LayoutParams titleParams = new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        );
        addView(title, titleParams);

        status = new TextView(context);
        status.setTextColor(Color.WHITE);
        status.setTextSize(11.5f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(5), dp(4), dp(5), dp(4));
        status.setBackgroundColor(0xC5181C24);
        LayoutParams statusParams = new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        addView(status, statusParams);

        showEmpty();
    }

    public void showEmpty() {
        accepted = false;
        lastScore = 0;
        lastMessage = "Appuyer puis aligner le sujet";
        preview.setImageDrawable(null);
        preview.setAlpha(0.0f);
        guide.setGuideColor(COLOR_WAITING);
        status.setText(lastMessage);
        status.setTextColor(Color.WHITE);
        setBackground(createBackground(COLOR_WAITING));
    }

    public void showAnalyzing() {
        accepted = false;
        lastScore = 0;
        lastMessage = "Analyse de la silhouette…";
        preview.setAlpha(0.35f);
        guide.setGuideColor(COLOR_WAITING);
        status.setText(lastMessage);
        status.setTextColor(Color.WHITE);
        setBackground(createBackground(COLOR_WAITING));
    }

    public void showResult(
            Bitmap bitmap,
            boolean isAccepted,
            int score,
            String message
    ) {
        accepted = isAccepted;
        lastScore = score;
        lastMessage = message;
        preview.setImageBitmap(bitmap);
        preview.setAlpha(isAccepted ? 0.72f : 0.55f);
        int color = isAccepted ? COLOR_VALID : COLOR_INVALID;
        guide.setGuideColor(color);
        status.setText((isAccepted ? "✓ " : "✕ ") + message);
        status.setTextColor(Color.WHITE);
        setBackground(createBackground(color));
    }

    public void showSequenceWarning(String message) {
        accepted = false;
        guide.setGuideColor(COLOR_INVALID);
        status.setText("✕ " + message);
        setBackground(createBackground(COLOR_INVALID));
    }

    public void restoreResult() {
        if (preview.getDrawable() == null) {
            showEmpty();
            return;
        }
        showResult(
                null,
                accepted,
                lastScore,
                lastMessage
        );
    }

    public void clearPreviewReference() {
        preview.setImageDrawable(null);
    }

    public int getViewIndex() {
        return viewIndex;
    }

    private GradientDrawable createBackground(int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.rgb(18, 21, 28));
        drawable.setCornerRadius(dp(12));
        drawable.setStroke(dp(2), strokeColor);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class GuideSilhouetteView extends View {
        private final int viewIndex;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int guideColor = COLOR_WAITING;

        GuideSilhouetteView(Context context, int viewIndex) {
            super(context);
            this.viewIndex = viewIndex;
            fillPaint.setStyle(Paint.Style.FILL);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(context, 2));
            axisPaint.setStyle(Paint.Style.STROKE);
            axisPaint.setStrokeWidth(dp(context, 1));
            axisPaint.setPathEffect(new DashPathEffect(
                    new float[]{dp(context, 5), dp(context, 5)},
                    0.0f
            ));
        }

        void setGuideColor(int color) {
            guideColor = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            if (width <= 0.0f || height <= 0.0f) {
                return;
            }

            axisPaint.setColor(withAlpha(guideColor, 95));
            canvas.drawLine(width * 0.5f, height * 0.08f,
                    width * 0.5f, height * 0.94f, axisPaint);
            canvas.drawLine(width * 0.18f, height * 0.91f,
                    width * 0.82f, height * 0.91f, axisPaint);

            float factor = ManualViewPlan.getGuideWidthFactor(viewIndex);
            float direction = viewIndex >= 1 && viewIndex <= 3
                    ? 1.0f
                    : (viewIndex >= 5 && viewIndex <= 7 ? -1.0f : 0.0f);
            float centerX = width * (0.5f + direction * 0.018f);
            float bodyWidth = width * factor;
            float shoulderHalf = bodyWidth * 0.34f;
            float torsoHalf = bodyWidth * 0.23f;
            float hipHalf = bodyWidth * 0.22f;
            float headRadius = Math.min(width, height) * (ManualViewPlan.isProfile(viewIndex)
                    ? 0.070f : 0.080f);

            fillPaint.setColor(withAlpha(guideColor, 42));
            strokePaint.setColor(withAlpha(guideColor, 230));

            float headY = height * 0.18f;
            canvas.drawCircle(
                    centerX + direction * headRadius * 0.20f,
                    headY,
                    headRadius,
                    fillPaint
            );
            canvas.drawCircle(
                    centerX + direction * headRadius * 0.20f,
                    headY,
                    headRadius,
                    strokePaint
            );

            Path body = new Path();
            body.moveTo(centerX - shoulderHalf, height * 0.31f);
            body.quadTo(centerX - torsoHalf * 1.25f, height * 0.39f,
                    centerX - torsoHalf, height * 0.56f);
            body.lineTo(centerX - hipHalf, height * 0.64f);
            body.lineTo(centerX - hipHalf * 0.82f, height * 0.73f);
            body.lineTo(centerX - hipHalf * 0.56f, height * 0.90f);
            body.lineTo(centerX - hipHalf * 0.08f, height * 0.90f);
            body.lineTo(centerX - hipHalf * 0.05f, height * 0.70f);
            body.lineTo(centerX + hipHalf * 0.05f, height * 0.70f);
            body.lineTo(centerX + hipHalf * 0.08f, height * 0.90f);
            body.lineTo(centerX + hipHalf * 0.56f, height * 0.90f);
            body.lineTo(centerX + hipHalf * 0.82f, height * 0.73f);
            body.lineTo(centerX + hipHalf, height * 0.64f);
            body.lineTo(centerX + torsoHalf, height * 0.56f);
            body.quadTo(centerX + torsoHalf * 1.25f, height * 0.39f,
                    centerX + shoulderHalf, height * 0.31f);
            body.quadTo(centerX + torsoHalf * 0.60f, height * 0.27f,
                    centerX + headRadius * 0.58f, height * 0.27f);
            body.lineTo(centerX + headRadius * 0.48f, height * 0.25f);
            body.lineTo(centerX - headRadius * 0.48f, height * 0.25f);
            body.lineTo(centerX - headRadius * 0.58f, height * 0.27f);
            body.quadTo(centerX - torsoHalf * 0.60f, height * 0.27f,
                    centerX - shoulderHalf, height * 0.31f);
            body.close();
            canvas.drawPath(body, fillPaint);
            canvas.drawPath(body, strokePaint);

            float armWidth = Math.max(width * 0.035f, bodyWidth * 0.07f);
            drawArm(canvas, centerX - shoulderHalf, height * 0.33f,
                    centerX - shoulderHalf * 1.07f, height * 0.66f,
                    armWidth);
            drawArm(canvas, centerX + shoulderHalf, height * 0.33f,
                    centerX + shoulderHalf * 1.07f, height * 0.66f,
                    armWidth);
        }

        private void drawArm(
                Canvas canvas,
                float startX,
                float startY,
                float endX,
                float endY,
                float armWidth
        ) {
            Paint armFill = new Paint(fillPaint);
            armFill.setStrokeWidth(armWidth * 2.0f);
            armFill.setStrokeCap(Paint.Cap.ROUND);
            armFill.setStyle(Paint.Style.STROKE);
            Paint armStroke = new Paint(strokePaint);
            armStroke.setStrokeWidth(Math.max(2.0f, armWidth * 0.22f));
            armStroke.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(startX, startY, endX, endY, armFill);
            canvas.drawLine(startX, startY, endX, endY, armStroke);
        }

        private static int withAlpha(int color, int alpha) {
            return Color.argb(
                    alpha,
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
            );
        }

        private static int dp(Context context, int value) {
            return Math.round(value * context.getResources()
                    .getDisplayMetrics().density);
        }
    }
}
