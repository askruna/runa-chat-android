package ai.askruna.chat;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.Build;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The chat screen while its page loads (1.1.1): the mark of Ask Quicklly in search's working line — the sparkle in a
 * spinning ring — and the assistant's name under it, on white, a little above the middle. It fades out once the page
 * says the chat has drawn ({@code shown}).
 */
final class LoadingView extends FrameLayout {

    // The Runa sparkle as the search answer draws it (its logo in #111), 96 px.
    private static final String SPARK_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAYAAADimHc4AAAMMElEQVR4AeydC4xUZxXHZ3Z2FpYBZmBZF5BWlKJtJAUl1pYSAtZX"
            + "UNraKtIg1ge2QFGJSltNaXxFqWklWlCCxYRHWrGRFC0SK0INEjS2oAJqH5aHPLo8dth1d7H7mPX3n53dzMzO4965d+7cz+7mnDn3"
            + "8T3OOf/vnu9xv5mtCgz+VdQDgwBU1P2BwCAAgwBU2AM2qo/FYm+ykdxS0sEnwJKbehNVVVWtiUajb+49c+dzEAAbfuzp6ZkGCBEb"
            + "WYomHQSgqIt6E9DyR3E0IRgMXoN0jYwGgJgcc80TRQrC8bNJEuYpeA/SNb+5VhBKeU6Eg496WOm1qbomNjQ01KaOHQtjAairq7uG"
            + "1viAYw9YK6AasCemkk6m3uGpY8fCWABwwgSsfwNsn2zmqK+vH0N970tlm9TZ2XlT6tixMBaARCKhVlg7atSoux17oUgB3d3dGnq+"
            + "MS3ZN9OOHR0aC0AwGEw6BPluRx6wkJnW/72sZJMYFbnyFBgLAA55PxzAOe+QLBfzhC2n7BlwBtEnfIELQ2BHZCoAQayeBwuAYSNG"
            + "jBijY7eZVj6JMpfBufw0Z/To0TdzT7ogSqNcBZdWkoe5Ro4cqUlRskZCUAOtcWryxMWPCRMm1FLuYxT5VjgXjaAfehQQ+jrnXGmK"
            + "XjMSgFAo9JU0y6KAcEXaudPDIbT86W1tbfsoSJOvEDInUW8DIfBxQFhMqIqSyPbTYCQAGHoH3E+0VDfsCOL4d+LITZT3KwqfDluh"
            + "OkBYDxjbybuQcFhnJVNfGjcU7yvLKxmmooyWhgNu5ZpdCk2cOHHo8OHD62nBH8N5cRz/PIV8HB4H26EQOmiJYkt1dfUFyltDmJzM"
            + "jFkLdwP0TS/YOAAw7hYMaIDTSR1yBijpN/uOtXaEoxcgVyHXNjc37wmHw2dw3s9JoxCCcE6Ut4Iw+WJHR8dh6tkCf4c6F+kJo/QM"
            + "n2eccNMEUrwfmq0oSxPXZV/LPr906VIL144SLsYiPwS/C66Gy0WawKlxzKbOLgA/QkUJuJ88BKC/TkcHtK6cY29GJFbWhRLxePww"
            + "fA98JU6ZhjKfR/4AeQy+DPfApVIXGU/DO+GvwrMISQ3UdQP8BOcdcAYZBwDaa1yOGEDXc6VoGCJNPzU1NR3FMWuRKwB2DkAojquV"
            + "dvcnsn5wlvyfoCHMIvzcQbmr4X3nz59vLVSEUQAwwtCESyEol021hKGrc92wco3wdAIg/ojTrgWM28lzHLbyNMjB62pqaiaTfxth"
            + "5pULFy78h7yWyCgAaFn3F7AqguMWFLhv+RZg7CDxzbTovchCpJBydyQSWdnY2NhWKGG+eyYBUIVDvpzPEF0HgDqkKzbxJBwmnHyJ"
            + "Os9RZi7q5OIK0j1+6tQp9R2c2idXlLVfrf0cDD9vs5BrfiwWG2khnaUkPAnqDx7Kk/jPLFNvy3PP8mVTAAjSumdZsKqeFnulhXRW"
            + "k3QT179P4ktwBvF0rGppaWnKuFjCiREA0Ko1Sep7J1vMzF0k0OwT4Q4BqvqW9JHRy3S2f3CjdCMAYIlgPMbOhK3QeAD7sJWEVtMA"
            + "gEKRJnHJLJxrjK8OOHnu5MMIAAg/azEy76ok97JpIRdqYFeotrZWoSbZ4tGlkQax35WCKcT3ALCOotY8B10tEy30veTLt45vuZy+"
            + "hBrl4PjndE7Zf2E43P806FohLnbP1wDopQiG69VfMTuy76vPyDdjzk5r6RzHv5RKeJIx/39Tx46FrwFob2+fieE3lGjlUvqCT5eY"
            + "d0A2Vk33cFEz40PI9A6Z09LJtwCMHz9+GK3/GUzT9hNESbSkvr7eSf7+Ss+dO9eIPi8R/5/tv+jCgV8BqL58+fJPndrH03NdV1eX"
            + "RixOi0rmx/mnmHyVPOtNFpL14UsA6EAXo6db+z5XMovW4hpFOqOhQ4fOY3Z8wlkpmbl9B0DKWd9CTTvDTpLnJe1ofoz+wMpMOm8h"
            + "unHmzJl2pPoBhDvkKwBw/nzi7GZM07IzwjWKEY628f7Xk72kdrT2CwAhWuhtOP9HKD8MLgeNZSTTSD3aauIXu33xLckhOOUBWugv"
            + "8LqWkxHlI+p5gj7mh9TgVoijqNKpki2hKhqNTsIZR3DK10s3wXbOseS4h3r/NGbMGNdmy5RZElUCAG2AugkHrMPxWlO5qiTNC2Wy"
            + "dm86Q8pd6LEaIN5mLYv7qbwGIIzB+xhP78aUJQDQgKwkvYXK7wOIf6LXo5r8ce4plR0AWte4WCz2RQzcDGsJ90ZPLbRe2XImf2fR"
            + "8SH0vZMRmWtv1gqpUC4AghgyEz5C63qelr4GJRbBfic5/V703cCI7Cj6HwCIt6O0re0upLdMrgCg9RaUnUfL+QhyKawX2dpdLOW1"
            + "z7JsBli21F5CvUvQd9CuB4gj2BOHl8k+ANH+I9feuJUMAIrMQKEnUWxvZ2fn77DvKVrOdqTG8m5PpCi2ohSldg0atgPI09i8B96L"
            + "D9bAAoTbpVExAMLEcL3im4qzp1LZZ5CvUnk3iuzH4Vqv0b5H7cssVlZpGvovl+Yqej06Gx9oR90B/JGA2+GF+Gcqcor8hupF5xo5"
            + "nUYBN8Lr4a3E8Gdw9EH4EBVuRGrkkjMfFb5eSSFWX97ein/0vuA5+Q3/bQaQjcgp+RyT05HxeHw/vCyRSCwh4y9hvYJrRmr3lzYk"
            + "cThIWR54jXNtU5SfztNYN+DDT7F6ehdSL/W5PZByApBKlmhubiZv/Gt8jIL16M2g4M+C8rdJsx4+Ab+e6QX88Qj+0Ort7TTYqfIT"
            + "fAWO13KHGmvBt2eFAMh2rLZ2/42CtzQ1Na2ikuXV1dVT6IAVkpZmJ/4/Pj+KbXNld01NzXT8sRJ/PIg/dtJgX+Fexv5/zguSHQCy"
            + "C+rW1uvW1tZzVL4+Ho8HI5GIXiPeSkItKT+NNJ20y3kHrXwjhszFzjA8Bd4lu3k5r5Ds6P2AEwDQKZO0fYMWsQMF74xEIvN5JKfz"
            + "eK4klb78gDCGTqL3fLSdwYLhAmxajE3acacvYHDZPXIVgHS1BAaP5EEez4dR/ipa0Se5r8mZvkHCoe/o32j0G/Schr6T0PtJ5JHj"
            + "x4+7tgWF8gdQ2QDIqilBK9qCQXMwUN+ZyrfjOCubJ6ct6KTl8LnoNxc9/0qtrrd0ysxJXgHQV3k3Bh7C0PsJT1qJ1HY/W51WX0Eu"
            + "SA0b99XW1o5Dp2+gk4aKnuviNQD9fiM8HWMU8UFi7X1c1MgC4RmdpdXfRT/1gdSLds8qzq6oYgBIEY0iiLUPczwX9oqeBfSrafWb"
            + "1U95VWm+eioKQJ9S8Xj8JMf6Ab7fI8sWBnD8JvgWQNfMnqoqT74AQG4ABE3h9S14jb11yW1eh+M/B/vG+TLQNwBIGYEAN9BKk1vB"
            + "dc0NJt7vDoVC2qKopQE3inStDF8BkLLqNQDQnEHj8tQlR2JvOBxeZOe7u3Zqc5rWjwAELl68+A8M+y7slNoAczVLJq86Lahc+X0J"
            + "gIwlFP0YuQF2QjuJ+dqB4aSMsub1LQCympcaGqKWuo7UAYgKZWUbVUlHp+xrAFpaWl4mhOiFkG076Xi1CKjZru28XmbwNQA4oqeq"
            + "qmoV0i79nVGP9prazed5er8DEEiNXgr9SEcup/2ajtyvq64Z+voeAGlLOPkZMg5bIt7UPWIpoQ8SGQEA6zan6Qv0EzJWXHbQz8PO"
            + "bAOMAAClu1i+PogsSqTTXqWi6fySwBQAAjwFRecEhKrdLHOf8YtzrehhDAAYo53V/0IGAnk+CFP63U+ly5PCf5dNAkC7Dx4s4ELt"
            + "v9GyttIVSOavWyYBEGBmq9FQPge3RqNRx1/u9hoeowDAOQnCjH7jk8MB1FPuHQwDanThgmkAyOScy9QA85RumsbGAcAwM+fiWkdH"
            + "x72mOV/6GgcAa0O/RfELcDo1tba2nk+/YMqxcQCwvq+XNdoGnu7jn6SfmHRsHAA4VyEo4w0XE7AXuG4kmQiA/nFP+tbGLjpgV3/D"
            + "x0skjQSAZQn9PHzST7T+i3TMLyZPDPywAYCvrNP2kmRHTOs/y/qPpYU6X1mQUsZUAAI4XlvdU2aYK4wFgLCzVW4nBG2SNJWNBQCH"
            + "J9eEampq9K9BODWTjAWAEKSl6dP6OUkzXd+rtbEAhEKhY4SfA71mmPtpLACp3RLGfxPTWADU5pkPGN0BywajAZABpvMgABVGcBCA"
            + "IgCU+/b/AAAA//93BGrbAAAABklEQVQDAI1D3+6anyC+AAAAAElFTkSuQmCC";

    private final View ring;
    private final LinearLayout group;
    private ObjectAnimator spin;

    LoadingView(Context context, String title) {
        super(context);
        float dp = getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.WHITE);
        setClickable(true);                               // nothing under it takes a tap while it shows
        setContentDescription("Loading " + title);

        group = new LinearLayout(context);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout mark = new FrameLayout(context);
        ring = new RingView(context, 2.5f * dp);
        mark.addView(ring, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView spark = new ImageView(context);
        byte[] png = Base64.decode(SPARK_PNG, Base64.DEFAULT);
        spark.setImageBitmap(BitmapFactory.decodeByteArray(png, 0, png.length));
        mark.addView(spark, new FrameLayout.LayoutParams((int) (20 * dp), (int) (20 * dp), Gravity.CENTER));
        group.addView(mark, new LinearLayout.LayoutParams((int) (44 * dp), (int) (44 * dp)));

        TextView name = new TextView(context);
        name.setText(title);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        name.setTextColor(0xFF111111);
        name.setTypeface(Build.VERSION.SDK_INT >= 28
                ? Typeface.create(Typeface.DEFAULT, 600, false)
                : Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (14 * dp);
        group.addView(name, lp);

        addView(group, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        group.setTranslationY(-0.08f * h);                // the group's middle at 42 % of the screen, where the eye lands
    }

    boolean isShowing() { return getVisibility() == VISIBLE; }

    void show() {
        animate().cancel();
        setAlpha(1f);
        setVisibility(VISIBLE);
        startSpin();
    }

    /** Fades out over 0.2 s, or at once (an error screen takes its place). */
    void hide(boolean fade) {
        if (!isShowing()) return;
        animate().cancel();
        if (!fade) { done(); return; }
        animate().alpha(0f).setDuration(200).withEndAction(this::done);
    }

    private void done() {
        setVisibility(GONE);
        setAlpha(1f);
        if (spin != null) spin.cancel();
    }

    private void startSpin() {
        if (spin == null) {
            // one turn every 0.9 s, like the search answer; with animations off in the phone's settings it stays still
            spin = ObjectAnimator.ofFloat(ring, View.ROTATION, 0f, 360f);
            spin.setDuration(900);
            spin.setRepeatCount(ValueAnimator.INFINITE);
            spin.setInterpolator(new LinearInterpolator());
        }
        if (!spin.isStarted()) spin.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isShowing()) startSpin();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (spin != null) spin.cancel();
        super.onDetachedFromWindow();
    }

    /** The ring: a black head fading back into a clear tail, the search answer's conic gradient. */
    private static final class RingView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private final float stroke;

        RingView(Context context, float stroke) {
            super(context);
            this.stroke = stroke;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float cx = w / 2f, cy = h / 2f;
            // clear to 120°, 35 % black at 220°, black at 340°, clear after (0° = 12 o'clock, clockwise)
            SweepGradient g = new SweepGradient(cx, cy,
                    new int[] { 0x00111111, 0x00111111, 0x59111111, 0xFF111111, 0x00111111, 0x00111111 },
                    new float[] { 0f, 120f / 360f, 220f / 360f, 340f / 360f, 341f / 360f, 1f });
            Matrix m = new Matrix();
            m.setRotate(-90f, cx, cy);                    // a sweep starts at 3 o'clock
            g.setLocalMatrix(m);
            paint.setShader(g);
            oval.set(stroke / 2f, stroke / 2f, w - stroke / 2f, h - stroke / 2f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawOval(oval, paint);
        }
    }
}
