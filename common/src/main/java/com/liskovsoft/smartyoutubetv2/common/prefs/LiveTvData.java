package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs.ProfileChangeListener;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

/**
 * MOD: TV Live (IPTV) config.<br/>
 * Stores either a single m3u/m3u8 playlist url (legacy) or a JSON multi-source config:<br/>
 * <pre>
 * [
 *   { "type": "m3u",  "url": "https://.../iptv.m3u" },
 *   { "type": "m3u8", "group": [
 *       { "name": "News", "url": [ { "name": "cctv1", "url": "https://.../cctv1.m3u8" } ] }
 *   ]}
 * ]
 * </pre>
 * Editable from the web config page (see {@link com.liskovsoft.smartyoutubetv2.common.server.Server}).
 */
public class LiveTvData implements ProfileChangeListener {
    private static final String LIVE_TV_DATA = "live_tv_data";
    public static final String DEFAULT_PLAYLIST_URL = "https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/iptv.m3u";
    @SuppressLint("StaticFieldLeak")
    private static LiveTvData sInstance;
    private final AppPrefs mPrefs;
    private String mConfig;
    private final Runnable mPersistDataInt = this::persistDataInt;

    private LiveTvData(Context context) {
        mPrefs = AppPrefs.instance(context);
        mPrefs.addListener(this);
        restoreData();
    }

    public static LiveTvData instance(Context context) {
        if (sInstance == null) {
            sInstance = new LiveTvData(context.getApplicationContext());
        }

        return sInstance;
    }

    /**
     * Raw config: a JSON multi-source array or a single playlist url (legacy).
     */
    public String getConfig() {
        return mConfig;
    }

    public void setConfig(String config) {
        mConfig = TextUtils.isEmpty(config) ? DEFAULT_PLAYLIST_URL : config.trim();
        persistData();
    }

    /**
     * MOD: TV Live / Page config split.<br/>
     * The raw config may now be an object holding two keys:<br/>
     * <pre>{ "m3u8": [ ...live sources... ], "page": [ ...page groups... ] }</pre>
     * Legacy formats (a bare playlist url, or a bare {@code [ ... ]} sources array) are
     * treated as the {@code m3u8} section, and the {@code page} section is empty.
     * Returns the live (m3u8) section: either a {@code [ ... ]} array string or a bare url.
     */
    public String getLiveConfig() {
        return extractSection("m3u8", mConfig);
    }

    /**
     * Returns the page section as a {@code [ ... ]} array string, or empty when absent.
     */
    public String getPageConfig() {
        return extractSection("page", "");
    }

    private String extractSection(String key, String legacyFallback) {
        String raw = mConfig;
        if (raw == null) {
            return legacyFallback;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            try {
                org.json.JSONObject obj = new org.json.JSONObject(trimmed);
                Object value = obj.opt(key);
                return value != null ? value.toString() : "";
            } catch (Exception e) {
                // malformed object: fall through to legacy handling
            }
        }
        // Legacy raw config (bare url or [ ... ] array) is the live section only
        return legacyFallback;
    }

    /** @deprecated kept for backward compat; use {@link #getConfig()}. */
    public String getPlaylistUrl() {
        return mConfig;
    }

    /** @deprecated kept for backward compat; use {@link #setConfig(String)}. */
    public void setPlaylistUrl(String playlistUrl) {
        setConfig(playlistUrl);
    }

    private void restoreData() {
        String data = mPrefs.getProfileData(LIVE_TV_DATA);

        String[] split = Helpers.splitData(data);

        mConfig = Helpers.parseStr(split, 0, DEFAULT_PLAYLIST_URL);
    }

    public void persistNow() {
        Utils.post(mPersistDataInt);
    }

    private void persistData() {
        Utils.postDelayed(mPersistDataInt, 10_000);
    }

    private void persistDataInt() {
        mPrefs.setProfileData(LIVE_TV_DATA, Helpers.mergeData(mConfig));
    }

    @Override
    public void onProfileChanged() {
        Utils.removeCallbacks(mPersistDataInt);
        restoreData();
    }
}
