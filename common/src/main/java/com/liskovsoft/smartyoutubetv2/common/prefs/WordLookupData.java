package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs.ProfileChangeListener;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

/**
 * MOD: subtitle word lookup config (endpoints/tokens).<br/>
 * Editable from the web config page (see {@link com.liskovsoft.smartyoutubetv2.common.server.Server}).
 */
public class WordLookupData implements ProfileChangeListener {
    private static final String WORD_LOOKUP_DATA = "word_lookup_data";
    // LLM translation worker
    public static final String DEFAULT_ENDPOINT = "https://subtrans.liuyongliang123.workers.dev";
    public static final String DEFAULT_AUTH_TOKEN = "st-subtrans-7f3d9a2c";
    // Generic KV storage (Cloudflare D1): lookup results + known words
    public static final String DEFAULT_KV_BASE = "https://kvdata.liuyongliang123.workers.dev/api/st_words";
    public static final String DEFAULT_KV_TOKEN = "1376464";
    @SuppressLint("StaticFieldLeak")
    private static WordLookupData sInstance;
    private final AppPrefs mPrefs;
    private String mEndpoint;
    private String mAuthToken;
    private String mKvBase;
    private String mKvToken;
    private final Runnable mPersistDataInt = this::persistDataInt;

    private WordLookupData(Context context) {
        mPrefs = AppPrefs.instance(context);
        mPrefs.addListener(this);
        restoreData();
    }

    public static WordLookupData instance(Context context) {
        if (sInstance == null) {
            sInstance = new WordLookupData(context.getApplicationContext());
        }

        return sInstance;
    }

    public String getEndpoint() {
        return mEndpoint;
    }

    public void setEndpoint(String endpoint) {
        mEndpoint = TextUtils.isEmpty(endpoint) ? DEFAULT_ENDPOINT : endpoint.trim();
        persistData();
    }

    public String getAuthToken() {
        return mAuthToken;
    }

    public void setAuthToken(String authToken) {
        mAuthToken = TextUtils.isEmpty(authToken) ? DEFAULT_AUTH_TOKEN : authToken.trim();
        persistData();
    }

    public String getKvBase() {
        return mKvBase;
    }

    public void setKvBase(String kvBase) {
        mKvBase = TextUtils.isEmpty(kvBase) ? DEFAULT_KV_BASE : kvBase.trim();
        persistData();
    }

    public String getKvToken() {
        return mKvToken;
    }

    public void setKvToken(String kvToken) {
        mKvToken = TextUtils.isEmpty(kvToken) ? DEFAULT_KV_TOKEN : kvToken.trim();
        persistData();
    }

    private void restoreData() {
        String data = mPrefs.getProfileData(WORD_LOOKUP_DATA);

        String[] split = Helpers.splitData(data);

        mEndpoint = Helpers.parseStr(split, 0, DEFAULT_ENDPOINT);
        mAuthToken = Helpers.parseStr(split, 1, DEFAULT_AUTH_TOKEN);
        mKvBase = Helpers.parseStr(split, 2, DEFAULT_KV_BASE);
        mKvToken = Helpers.parseStr(split, 3, DEFAULT_KV_TOKEN);
    }

    public void persistNow() {
        Utils.post(mPersistDataInt);
    }

    private void persistData() {
        Utils.postDelayed(mPersistDataInt, 10_000);
    }

    private void persistDataInt() {
        mPrefs.setProfileData(WORD_LOOKUP_DATA, Helpers.mergeData(
                mEndpoint, mAuthToken, mKvBase, mKvToken
                ));
    }

    @Override
    public void onProfileChanged() {
        Utils.removeCallbacks(mPersistDataInt);
        restoreData();
    }
}
