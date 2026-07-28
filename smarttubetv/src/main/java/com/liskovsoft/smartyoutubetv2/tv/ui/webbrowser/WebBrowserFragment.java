package com.liskovsoft.smartyoutubetv2.tv.ui.webbrowser;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.WebBrowserPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.WebBrowserView;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * MOD: Full-screen WebView page (Page feature). Renders a configured url with a desktop
 * user-agent, a forced 1920px viewport and a D-pad virtual cursor (move/scroll/click),
 * so a remote-only TV can browse an arbitrary web page. Ported from the TV-New WebFragment.
 */
public class WebBrowserFragment extends Fragment implements WebBrowserView, CursorWebView.Listener {
    private CursorWebView mWebView;
    private ImageView mCursor;
    private ProgressBar mProgress;
    private WebBrowserPresenter mWebBrowserPresenter;
    private float mCursorX;
    private float mCursorY;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mWebBrowserPresenter = WebBrowserPresenter.instance(getContext());
        mWebBrowserPresenter.setView(this);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        try {
            return inflater.inflate(R.layout.webbrowser, null);
        } catch (Exception e) { // Failed to load WebView provider: No WebView installed
            e.printStackTrace();
        }

        return null;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mWebView = view.findViewById(R.id.webview);
        mCursor = view.findViewById(R.id.web_cursor);
        mProgress = view.findViewById(R.id.web_progress);
        mWebView.setBackgroundColor(Color.BLACK);

        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(getDesktopUserAgent(settings));
        // Force 1:1 rendering: on a density-2 TV the default viewport is only 960px, triggering
        // a narrow (mobile) layout that gets scaled up and blurry.
        mWebView.setInitialScale(100);

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                return !url.startsWith("http");
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                showProgress();
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                injectViewport(view);
                hideProgress();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectViewport(view);
                hideProgress();
            }
        });

        mWebView.setListener(this);
        mWebView.requestFocus();
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mWebBrowserPresenter.onViewInitialized();
    }

    @Override
    public void loadUrl(String url) {
        if (mWebView != null && url != null) {
            mWebView.loadUrl(url);
            mWebView.requestFocus();
        }
    }

    // A desktop page without a viewport meta is laid out at a fixed 980px by the WebView;
    // inject width=<view width> to get the full desktop viewport. Skip when unchanged to avoid relayout.
    private void injectViewport(WebView view) {
        int width = view.getWidth() > 0 ? view.getWidth() : 1920;
        view.evaluateJavascript("(function(){var c='width=" + width + "';var m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}if(m.content!==c)m.content=c;})();", null);
    }

    // Mimic Chrome "request desktop site": rewrite the real default UA, keeping the engine version.
    private String getDesktopUserAgent(WebSettings settings) {
        String ua = settings.getUserAgentString();
        ua = ua.replaceAll("\\(Linux;[^)]*\\)", "(Windows NT 10.0; Win64; x64)");
        ua = ua.replace("Version/4.0 ", "").replace(" Mobile Safari/", " Safari/");
        return ua;
    }

    @Override
    public boolean onKey(KeyEvent event) {
        int keyCode = event.getKeyCode();

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (mWebView.canGoBack()) {
                    mWebView.goBack();
                    return true;
                }
                return false; // let the activity finish
            }
            // Consume the DOWN when we will handle the UP, to stop the WebView's own back navigation
            return mWebView.canGoBack();
        }

        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return isCursorKey(keyCode);
        }

        int step = getStep(event.getRepeatCount());
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveCursor(-step, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveCursor(step, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                moveCursor(0, -step);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveCursor(0, step);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0) {
                    performCursorClick();
                }
                return true;
            default:
                return false;
        }
    }

    private boolean isCursorKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return true;
            default:
                return false;
        }
    }

    private int getStep(int repeat) {
        int base = dp2px(10);
        return (int) (base * Math.min(1 + repeat / 5f, 5f));
    }

    private void moveCursor(int dx, int dy) {
        showCursor();
        float nx = mCursorX + dx;
        float ny = mCursorY + dy;
        int w = mWebView.getWidth();
        int h = mWebView.getHeight();
        if (ny < 0) {
            if (mWebView.canScrollVertically(-1)) mWebView.scrollBy(0, (int) ny);
            ny = 0;
        } else if (ny > h) {
            if (mWebView.canScrollVertically(1)) mWebView.scrollBy(0, (int) (ny - h));
            ny = h;
        }
        if (nx < 0) {
            if (mWebView.canScrollHorizontally(-1)) mWebView.scrollBy((int) nx, 0);
            nx = 0;
        } else if (nx > w) {
            if (mWebView.canScrollHorizontally(1)) mWebView.scrollBy((int) (nx - w), 0);
            nx = w;
        }
        mCursorX = nx;
        mCursorY = ny;
        mCursor.setTranslationX(mCursorX);
        mCursor.setTranslationY(mCursorY);
    }

    private void performCursorClick() {
        showCursor();
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, mCursorX, mCursorY, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, mCursorX, mCursorY, 0);
        mWebView.dispatchTouchEvent(down);
        mWebView.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private void showCursor() {
        if (mCursor.getVisibility() != View.VISIBLE) {
            if (mCursorX == 0 && mCursorY == 0) {
                resetCursor();
            }
            mCursor.setVisibility(View.VISIBLE);
        }
        mCursor.removeCallbacks(mHideCursor);
        mCursor.postDelayed(mHideCursor, 5000);
    }

    private void resetCursor() {
        mCursorX = mWebView.getWidth() / 2f;
        mCursorY = mWebView.getHeight() / 2f;
        mCursor.setTranslationX(mCursorX);
        mCursor.setTranslationY(mCursorY);
    }

    private final Runnable mHideCursor = () -> {
        if (mCursor != null) {
            mCursor.setVisibility(View.GONE);
        }
    };

    private void showProgress() {
        if (mProgress != null) {
            mProgress.setVisibility(View.VISIBLE);
        }
    }

    private void hideProgress() {
        if (mProgress != null) {
            mProgress.setVisibility(View.GONE);
        }
    }

    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mWebView != null) {
            mWebView.onResume();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mWebView != null) {
            mWebView.onPause();
        }
    }

    @Override
    public void onDestroyView() {
        if (mCursor != null) {
            mCursor.removeCallbacks(mHideCursor);
        }
        if (mWebView != null) {
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroyView();
    }
}
