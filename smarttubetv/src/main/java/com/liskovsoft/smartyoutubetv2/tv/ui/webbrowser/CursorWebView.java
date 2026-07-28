package com.liskovsoft.smartyoutubetv2.tv.ui.webbrowser;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.webkit.WebView;

/**
 * MOD: WebView that forwards raw key events to a listener before its own handling,
 * so the host fragment can implement a D-pad virtual cursor (Page feature).
 */
public class CursorWebView extends WebView {
    private Listener mListener;

    public interface Listener {
        boolean onKey(KeyEvent event);
    }

    public CursorWebView(Context context) {
        super(context);
    }

    public CursorWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CursorWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mListener != null && mListener.onKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
