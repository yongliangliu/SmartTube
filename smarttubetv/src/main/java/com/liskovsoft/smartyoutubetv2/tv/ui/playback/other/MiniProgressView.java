package com.liskovsoft.smartyoutubetv2.tv.ui.playback.other;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.ProgressBar;

/**
 * MOD: bilibili-style thin progress line pinned to the bottom of the player screen.<br/>
 * Visible only when the player controls are hidden. Updates itself while attached.
 */
public class MiniProgressView extends ProgressBar {
    private static final int UPDATE_INTERVAL_MS = 500;

    public interface PositionProvider {
        long getPositionMs();
        long getDurationMs();
        long getBufferedMs();
        boolean isUiVisible();
    }

    private PositionProvider mProvider;
    private final Runnable mUpdate = this::update;

    public MiniProgressView(Context context) {
        super(context);
    }

    public MiniProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MiniProgressView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setPositionProvider(PositionProvider provider) {
        mProvider = provider;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(mUpdate);
        post(mUpdate);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mUpdate);
        super.onDetachedFromWindow();
    }

    @SuppressLint("NewApi")
    private void update() {
        if (mProvider != null) {
            long durationMs = mProvider.getDurationMs();
            boolean show = durationMs > 0 && !mProvider.isUiVisible();
            setVisibility(show ? VISIBLE : GONE);

            if (show) {
                setProgress((int) (getMax() * mProvider.getPositionMs() / durationMs));
                setSecondaryProgress((int) (getMax() * mProvider.getBufferedMs() / durationMs));
            }
        }

        postDelayed(mUpdate, UPDATE_INTERVAL_MS);
    }
}
