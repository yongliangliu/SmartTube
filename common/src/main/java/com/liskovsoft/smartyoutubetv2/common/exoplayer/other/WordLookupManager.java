package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Subtitle word lookup (islandRadio style).<br/>
 * OK pauses the video and enters word selection, LEFT/RIGHT move the highlight,
 * OK again translates (tooltip card). The card auto closes in 8s and playback resumes;
 * BACK while the card is shown returns to selection (still paused),
 * BACK in selection exits and resumes playback (BACK never enters the mode:
 * it must keep its default role of leaving the player).<br/>
 * Lookup results and the "known words" list are persisted in the kvdata KV API,
 * known words are highlighted in subtitles during normal playback.
 */
public class WordLookupManager {
    private static final String TAG = WordLookupManager.class.getSimpleName();
    // LLM translation worker
    private static final String ENDPOINT = "https://subtrans.liuyongliang123.workers.dev";
    private static final String AUTH_TOKEN = "st-subtrans-7f3d9a2c";
    // Generic KV storage (Cloudflare D1): lookup results + known words
    private static final String KV_BASE = "https://kvdata.liuyongliang123.workers.dev/api/st_words";
    private static final String KV_TOKEN = "1376464";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final int CURSOR_BG = 0x59FFFFFF; // translucent light box: selection cursor
    private static final int KNOWN_WORD_FG = 0xFFFFEB3B; // yellow: already looked up words
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*");
    private static final int AUTO_CLOSE_MS = 8000;
    private static final Map<String, JSONObject> sCache = new HashMap<>(); // word (lower) -> result
    private static final Set<String> sKnownWords = Collections.synchronizedSet(new HashSet<>());
    private static volatile long sLastKnownWordsLoadMs;
    private static final long KNOWN_WORDS_REFRESH_MS = 30_000;

    private static final int STATE_IDLE = 0;
    private static final int STATE_SELECT = 1;
    private static final int STATE_CARD = 2;

    public interface PlaybackController {
        void setPlay(boolean play);
        boolean isPlaying();
    }

    private final SubtitleView mSubtitleView;
    private final Context mContext;
    private final PlaybackController mController;
    private final Runnable mRestoreStyles;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mAutoClose = this::exit; // auto close: leave the mode and resume playback

    private int mState = STATE_IDLE;
    private List<Cue> mCues; // last rendered cues (raw, no selection highlight)
    private String mText = ""; // text of the last cue (selection target)
    private final List<int[]> mWordSpans = new ArrayList<>(); // start/end offsets in mText
    private int mWordIndex;
    private boolean mWasPlaying;
    private String mPendingKey; // dedupe of in-flight request

    private LinearLayout mCard;
    private TextView mCardTitle;
    private TextView mCardBody;

    public WordLookupManager(SubtitleView subtitleView, PlaybackController controller, Runnable restoreStyles) {
        mSubtitleView = subtitleView;
        mContext = subtitleView.getContext();
        mController = controller;
        mRestoreStyles = restoreStyles;

        refreshKnownWords();
    }

    /** Called by SubtitleManager with the final (post-processed) cue list. */
    public void onCues(List<Cue> cues) {
        mCues = cues;

        if (mState == STATE_IDLE) {
            mText = extractText(cues);
        } else if (mController != null && mController.isPlaying()) {
            // Self-heal: the mode pauses playback, so new cues while playing mean
            // a stale state (video changed, key focus lost etc.). Exit the mode,
            // otherwise all fresh subtitles would be swallowed.
            abort();
            mText = extractText(cues);
        }
    }

    public boolean isActive() {
        return mState != STATE_IDLE;
    }

    private boolean isEnabled() {
        return PlayerTweaksData.instance(mContext).isSubtitleWordLookupEnabled();
    }

    /**
     * Highlight known (already looked up) words in the idle subtitle.<br/>
     * Returns the original list when nothing to decorate.
     */
    public List<Cue> decorate(List<Cue> cues) {
        if (!isEnabled() || cues == null || cues.isEmpty() || sKnownWords.isEmpty()) {
            return cues;
        }

        List<Cue> result = null;

        for (int i = 0; i < cues.size(); i++) {
            Cue cue = cues.get(i);
            CharSequence decorated = decorateText(cue.text);

            if (decorated != null) {
                if (result == null) {
                    result = new ArrayList<>(cues);
                }
                result.set(i, new Cue(decorated));
            }
        }

        if (result != null) {
            mSubtitleView.setApplyEmbeddedStyles(true); // render our spans
            return result;
        }

        if (mState == STATE_IDLE) {
            mSubtitleView.setApplyEmbeddedStyles(false); // back to the default mode
        }

        return cues;
    }

    private CharSequence decorateText(CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }

        String plain = text.toString();
        SpannableStringBuilder sb = null;

        Matcher matcher = WORD_PATTERN.matcher(plain);
        while (matcher.find()) {
            if (sKnownWords.contains(plain.substring(matcher.start(), matcher.end()).toLowerCase())) {
                if (sb == null) {
                    sb = new SpannableStringBuilder(plain);
                }
                // Known words: plain yellow glyphs, no box (same as everywhere else)
                sb.setSpan(new ForegroundColorSpan(KNOWN_WORD_FG), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        return sb;
    }

    /** @return true when the event is consumed */
    public boolean onKeyEvent(KeyEvent event) {
        if (!isEnabled()) {
            return false;
        }

        if (mSubtitleView == null || mSubtitleView.getVisibility() != View.VISIBLE) {
            if (isActive()) {
                exit();
            }
            return false;
        }

        int keyCode = event.getKeyCode();
        boolean isDown = event.getAction() == KeyEvent.ACTION_DOWN;

        if (mState == STATE_IDLE) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    // OK: pause and enter word selection
                    if (!hasWords()) {
                        return false;
                    }
                    if (isDown) {
                        enter(false);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    // Lookup mode replaces LEFT/RIGHT seeking: enter selection right away
                    if (!hasWords()) {
                        return true; // still swallow: no seeking in lookup mode
                    }
                    if (isDown) {
                        enter(keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    // BACK must keep its default role (exit the player page)
                    return false;
                default:
                    return false;
            }
        }

        // Active (SELECT or CARD)
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (isDown) {
                    hideCard();
                    moveSelection(-1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (isDown) {
                    hideCard();
                    moveSelection(1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (isDown) {
                    if (mState == STATE_CARD) {
                        exit(); // close the card and resume playback
                    } else {
                        translateSelected();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (isDown) {
                    if (mState == STATE_CARD) {
                        hideCard(); // back to word selection, video stays paused
                    } else {
                        exit();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true; // swallow: keep player overlay hidden while selecting
            default:
                // Unknown key: leave the mode and let the player handle it
                exit();
                return false;
        }
    }

    /** Force quit (subs hidden, video changed etc.). Doesn't touch playback state. */
    public void abort() {
        if (isActive()) {
            hideCard();
            mHandler.removeCallbacks(mAutoClose);
            mState = STATE_IDLE;
            restoreSubtitles();
        }
    }

    private void enter(boolean fromLeft) {
        mText = extractText(mCues);
        splitWords();

        if (mWordSpans.isEmpty()) {
            return;
        }

        mWasPlaying = mController == null || mController.isPlaying();
        if (mController != null) {
            mController.setPlay(false);
        }

        mWordIndex = fromLeft ? mWordSpans.size() - 1 : 0;
        mState = STATE_SELECT;
        renderHighlight();

        Log.d(TAG, "Word select mode ON, words: " + mWordSpans.size());
    }

    private void exit() {
        mHandler.removeCallbacks(mAutoClose);
        hideCard();
        mState = STATE_IDLE;
        restoreSubtitles();

        if (mController != null && mWasPlaying) {
            mController.setPlay(true);
        }

        Log.d(TAG, "Word select mode OFF");
    }

    private boolean hasWords() {
        return mText != null && WORD_PATTERN.matcher(mText).find();
    }

    private void splitWords() {
        mWordSpans.clear();

        if (mText == null) {
            return;
        }

        Matcher matcher = WORD_PATTERN.matcher(mText);
        while (matcher.find()) {
            mWordSpans.add(new int[]{matcher.start(), matcher.end()});
        }
    }

    private void moveSelection(int delta) {
        if (mWordSpans.isEmpty()) {
            return;
        }

        mWordIndex = Math.max(0, Math.min(mWordSpans.size() - 1, mWordIndex + delta));
        mState = STATE_SELECT;
        renderHighlight();
    }

    private String getSelectedWord() {
        if (mWordIndex < 0 || mWordIndex >= mWordSpans.size()) {
            return null;
        }

        int[] span = mWordSpans.get(mWordIndex);
        return mText.substring(span[0], span[1]);
    }

    private void renderHighlight() {
        if (mWordSpans.isEmpty()) {
            return;
        }

        int[] span = mWordSpans.get(mWordIndex);
        SpannableStringBuilder sb = new SpannableStringBuilder(mText);

        // known words keep their yellow glyphs in selection mode too (cursor word included)
        for (int[] word : mWordSpans) {
            if (sKnownWords.contains(mText.substring(word[0], word[1]).toLowerCase())) {
                sb.setSpan(new ForegroundColorSpan(KNOWN_WORD_FG), word[0], word[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        // cursor: a light translucent box only, glyph color stays as is
        sb.setSpan(new BackgroundColorSpan(CURSOR_BG), span[0], span[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        List<Cue> cues = new ArrayList<>();
        if (mCues != null && mCues.size() > 1) { // keep preceding cues as is
            cues.addAll(mCues.subList(0, mCues.size() - 1));
        }
        cues.add(new Cue(sb));

        mSubtitleView.setApplyEmbeddedStyles(true); // enable spans rendering
        mSubtitleView.setCues(cues);
    }

    private void restoreSubtitles() {
        if (mRestoreStyles != null) {
            mRestoreStyles.run(); // resets setApplyEmbeddedStyles(false) etc.
        }
        mSubtitleView.setCues(decorate(mCues));
    }

    private static String extractText(List<Cue> cues) {
        if (cues == null || cues.isEmpty()) {
            return "";
        }

        // selection target is the last non-empty cue (usually the only one)
        for (int i = cues.size() - 1; i >= 0; i--) {
            CharSequence text = cues.get(i).text;
            if (!TextUtils.isEmpty(text) && text.toString().trim().length() > 0) {
                return text.toString();
            }
        }

        return "";
    }

    // ------------------- Translation -------------------

    private void translateSelected() {
        String word = getSelectedWord();
        if (word == null) {
            return;
        }

        String sentence = mText.replace("\n", " ").trim();
        String cacheKey = word.toLowerCase();

        mState = STATE_CARD;

        JSONObject cached = sCache.get(cacheKey);
        if (cached != null) {
            showResultCard(word, sentence, cached);
            return;
        }

        showCard(word, "翻译中…");
        mPendingKey = cacheKey;

        // 1. try the KV storage first, 2. fallback to the LLM worker
        Request kvGet = new Request.Builder()
                .url(KV_BASE + "/" + urlEncode(cacheKey))
                .header("Authorization", "Bearer " + KV_TOKEN)
                .build();

        OkHttpManager.instance().getClient().newCall(kvGet).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.d(TAG, "KV miss (network): " + e.getMessage());
                translateRemote(cacheKey, word, sentence);
            }

            @Override
            public void onResponse(Call call, Response response) {
                JSONObject value = null;
                try {
                    if (response.code() == 200 && response.body() != null) {
                        JSONObject json = new JSONObject(response.body().string());
                        JSONObject item = json.optJSONObject("item");
                        value = item != null ? item.optJSONObject("value") : null;
                    }
                } catch (Exception e) {
                    Log.d(TAG, "KV parse error: " + e.getMessage());
                } finally {
                    response.close();
                }

                if (value != null) {
                    sCache.put(cacheKey, value);
                    sKnownWords.add(cacheKey);
                    postResult(cacheKey, word, sentence, value, null);
                } else {
                    translateRemote(cacheKey, word, sentence);
                }
            }
        });
    }

    private void translateRemote(String cacheKey, String word, String sentence) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("word", word);
            payload.put("sentence", sentence);
        } catch (Exception e) {
            Log.e(TAG, "payload error: " + e.getMessage());
            return;
        }

        Request request = new Request.Builder()
                .url(ENDPOINT)
                .header("X-Auth-Token", AUTH_TOKEN)
                .post(RequestBody.create(JSON_TYPE, payload.toString()))
                .build();

        OkHttpManager.instance().getClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "translate failed: " + e.getMessage());
                postResult(cacheKey, word, sentence, null, "翻译失败: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String body = response.body() != null ? response.body().string() : "";
                    if (response.code() != 200) {
                        postResult(cacheKey, word, sentence, null, "翻译失败 (HTTP " + response.code() + ")");
                        return;
                    }
                    JSONObject json = new JSONObject(body);
                    try {
                        json.put("sentence", sentence); // remember the lookup context
                    } catch (Exception ignored) {
                    }
                    sCache.put(cacheKey, json);
                    sKnownWords.add(cacheKey);
                    saveToKv(cacheKey, json);
                    postResult(cacheKey, word, sentence, json, null);
                } catch (Exception e) {
                    Log.e(TAG, "translate parse failed: " + e.getMessage());
                    postResult(cacheKey, word, sentence, null, "翻译结果解析失败");
                } finally {
                    response.close();
                }
            }
        });
    }

    // ------------------- KV storage -------------------

    /** Pull the known words list from KV. Throttled; called on every video load. */
    public void refreshKnownWords() {
        long now = System.currentTimeMillis();
        if (now - sLastKnownWordsLoadMs < KNOWN_WORDS_REFRESH_MS || !isEnabled()) {
            return;
        }
        sLastKnownWordsLoadMs = now;

        loadKnownWordsPage(0);
    }

    private void loadKnownWordsPage(int offset) {
        Request request = new Request.Builder()
                .url(KV_BASE + "?limit=200&offset=" + offset)
                .header("Authorization", "Bearer " + KV_TOKEN)
                .build();

        OkHttpManager.instance().getClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                sLastKnownWordsLoadMs = 0; // retry on the next video
                Log.d(TAG, "KV known words load failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                int count = 0;
                try {
                    if (response.code() != 200 || response.body() == null) {
                        return;
                    }
                    JSONObject json = new JSONObject(response.body().string());
                    JSONArray items = json.optJSONArray("items");
                    if (items == null) {
                        return;
                    }
                    count = items.length();
                    for (int i = 0; i < count; i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item == null) {
                            continue;
                        }
                        String key = item.optString("key", "");
                        if (!key.isEmpty()) {
                            sKnownWords.add(key);
                            JSONObject value = item.optJSONObject("value");
                            if (value != null) {
                                sCache.put(key, value);
                            }
                        }
                    }
                    Log.d(TAG, "KV known words loaded: " + sKnownWords.size());
                } catch (Exception e) {
                    Log.d(TAG, "KV known words parse failed: " + e.getMessage());
                } finally {
                    response.close();
                }

                if (count == 200) { // full page: there may be more
                    loadKnownWordsPage(offset + 200);
                }
            }
        });
    }

    private void saveToKv(String key, JSONObject value) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("key", key);
            payload.put("value", value);
        } catch (Exception e) {
            return;
        }

        Request request = new Request.Builder()
                .url(KV_BASE)
                .header("Authorization", "Bearer " + KV_TOKEN)
                .post(RequestBody.create(JSON_TYPE, payload.toString()))
                .build();

        OkHttpManager.instance().getClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.d(TAG, "KV save failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                response.close();
            }
        });
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    // ------------------- Card (tooltip style) -------------------

    private void postResult(String cacheKey, String word, String sentence, JSONObject json, String error) {
        mHandler.post(() -> {
            // ignore stale responses (user moved on or exited)
            if (mState != STATE_CARD || !cacheKey.equals(mPendingKey)) {
                return;
            }

            if (json != null) {
                showResultCard(word, sentence, json);
            } else {
                showCard(word, error);
                scheduleAutoClose();
            }
        });
    }

    private void showResultCard(String word, String sentence, JSONObject json) {
        // the word just became "known": repaint the subtitle right away (yellow glyphs)
        renderHighlight();

        SpannableStringBuilder title = new SpannableStringBuilder(word);
        String phonetic = json.optString("phonetic", "");
        if (!phonetic.isEmpty()) {
            title.append("  ").append(phonetic);
        }

        SpannableStringBuilder body = new SpannableStringBuilder();
        appendLine(body, "释义", json.optString("meaning", ""));
        appendLine(body, "词根", json.optString("rootAnalysis", ""));
        appendLine(body, "拼读", json.optString("syllableBreakdown", ""));
        appendLine(body, "例句", json.optString("example", ""));

        // sentence translation is context-bound: show only when it matches the current subtitle
        String cachedSentence = json.optString("sentence", "");
        if (cachedSentence.isEmpty() || cachedSentence.equals(sentence)) {
            appendLine(body, "整句", json.optString("sentenceTranslation", ""));
        }

        JSONArray levels = json.optJSONArray("levels");
        if (levels != null && levels.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < levels.length(); i++) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                sb.append(levels.optString(i));
            }
            appendLine(body, "等级", sb.toString());
        }

        showCard(title, body);
        scheduleAutoClose();
    }

    private void scheduleAutoClose() {
        mHandler.removeCallbacks(mAutoClose);
        mHandler.postDelayed(mAutoClose, AUTO_CLOSE_MS);
    }

    private static void appendLine(SpannableStringBuilder body, String label, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }

        if (body.length() > 0) {
            body.append("\n");
        }

        int start = body.length();
        body.append(label).append(": ");
        body.setSpan(new ForegroundColorSpan(0xFF80CBC4), start, body.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        body.append(value);
    }

    private void showCard(CharSequence title, CharSequence body) {
        ensureCard();
        mCardTitle.setText(title);
        mCardBody.setText(body);
        mCard.setVisibility(View.VISIBLE);
    }

    private void hideCard() {
        mPendingKey = null;
        mHandler.removeCallbacks(mAutoClose);
        if (mCard != null) {
            mCard.setVisibility(View.GONE);
        }
        if (mState == STATE_CARD) {
            mState = STATE_SELECT;
        }
    }

    private void ensureCard() {
        if (mCard != null) {
            return;
        }

        ViewGroup root = (ViewGroup) mSubtitleView.getParent();

        mCard = new LinearLayout(mContext);
        mCard.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        mCard.setPadding(pad, pad, pad, pad);

        // antd tooltip-like: dark rounded card with subtle border, sitting above the subtitles
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEB101418);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0x66FFFFFF);
        mCard.setBackground(bg);
        mCard.setElevation(dp(8));

        mCardTitle = new TextView(mContext);
        mCardTitle.setTextColor(Color.WHITE);
        mCardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mCardTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        mCardTitle.setMaxWidth(dp(500));
        mCard.addView(mCardTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mCardBody = new TextView(mContext);
        mCardBody.setTextColor(0xFFE0E0E0);
        mCardBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        mCardBody.setLineSpacing(dp(4), 1.0f);
        mCardBody.setMaxWidth(dp(500));
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = dp(10);
        mCard.addView(mCardBody, bodyParams);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = dp(150); // right above the subtitles, tooltip-like
        root.addView(mCard, params);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                mContext.getResources().getDisplayMetrics());
    }
}
