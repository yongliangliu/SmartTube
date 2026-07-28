package com.liskovsoft.smartyoutubetv2.common.misc;

import android.annotation.SuppressLint;
import android.content.Context;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.LiveTvData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.reactivex.Observable;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * MOD: TV Live (IPTV) section backend.<br/>
 * Loads a remote m3u/m3u8 playlist (configurable via {@link LiveTvData}) and converts
 * its entries into {@link Video} items playable by the internal ExoPlayer.
 */
public class LiveTvService {
    private static final String TAG = LiveTvService.class.getSimpleName();
    /**
     * Sidebar section id. NOTE: keep below 30 (built-in range, see BrowseSection.isDefault)
     * and above the last MediaGroup.TYPE_* constant to avoid collisions.
     */
    public static final int SECTION_ID = 23;
    private static final String VIDEO_ID_PREFIX = "iptv:";
    private static final long CACHE_TTL_MS = 10 * 60 * 1_000;
    private static final Pattern ATTR_PATTERN = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    @SuppressLint("StaticFieldLeak")
    private static LiveTvService sInstance;
    private final Context mContext;
    private List<Video> mCachedChannels;
    private String mCachedUrl;
    private long mCacheTimeMs;

    private LiveTvService(Context context) {
        mContext = context.getApplicationContext();
    }

    public static LiveTvService instance(Context context) {
        if (sInstance == null) {
            sInstance = new LiveTvService(context.getApplicationContext());
        }

        return sInstance;
    }

    public static boolean isLiveTvChannel(Video video) {
        return video != null && video.videoId != null && video.videoId.startsWith(VIDEO_ID_PREFIX);
    }

    public static String getStreamUrl(Video video) {
        return isLiveTvChannel(video) ? video.videoId.substring(VIDEO_ID_PREFIX.length()) : null;
    }

    public Observable<List<Video>> getChannelsObserve() {
        return RxHelper.fromCallable(this::getChannels);
    }

    // MOD: TV Live grouped rows (each group is a horizontal row)
    public Observable<List<ChannelGroup>> getChannelGroupsObserve() {
        return RxHelper.fromCallable(this::getChannelGroups);
    }

    /**
     * Blocking call. Run on a worker thread.
     */
    public List<Video> getChannels() {
        String config = LiveTvData.instance(mContext).getLiveConfig();

        if (mCachedChannels != null && config.equals(mCachedUrl)
                && System.currentTimeMillis() - mCacheTimeMs < CACHE_TTL_MS) {
            return mCachedChannels;
        }

        List<Video> channels = parseConfig(config);

        if (!channels.isEmpty()) {
            mCachedChannels = channels;
            mCachedUrl = config;
            mCacheTimeMs = System.currentTimeMillis();
        }

        return channels;
    }

    /**
     * Blocking call. Run on a worker thread. Channels aggregated by group, preserving order.
     */
    public List<ChannelGroup> getChannelGroups() {
        return groupChannels(getChannels());
    }

    /**
     * Last successfully loaded channels (no network access).
     */
    public List<Video> getCachedChannels() {
        return mCachedChannels != null ? mCachedChannels : new ArrayList<>();
    }

    /**
     * Group the flat channel list by group name, preserving first-seen order.
     */
    public List<ChannelGroup> groupChannels(List<Video> channels) {
        Map<String, ChannelGroup> groups = new LinkedHashMap<>();
        String fallback = mContext.getString(R.string.header_tv_live);

        for (Video channel : channels) {
            String name = channel.category != null && !channel.category.isEmpty() ? channel.category : fallback;
            ChannelGroup group = groups.get(name);
            if (group == null) {
                group = new ChannelGroup(name);
                groups.put(name, group);
            }
            group.channels.add(channel);
        }

        return new ArrayList<>(groups.values());
    }

    private String fetchPlaylist(String playlistUrl) {
        try {
            Request request = new Request.Builder().url(playlistUrl).build();
            Response response = OkHttpManager.instance().getClient().newCall(request).execute();
            ResponseBody body = response.body();
            return body != null ? body.string() : null;
        } catch (Exception e) {
            Log.e(TAG, "Can't fetch playlist %s: %s", playlistUrl, e.getMessage());
            // MOD: proxy mode: mark the host as direct and retry the playlist once without the proxy
            if (LiveProxyBypass.markFailed(playlistUrl)) {
                return fetchPlaylistDirect(playlistUrl);
            }
            return null;
        }
    }

    // MOD: same fetch but with an explicit NO_PROXY client (the shared client may keep the proxy selector)
    private String fetchPlaylistDirect(String playlistUrl) {
        try {
            Request request = new Request.Builder().url(playlistUrl).build();
            Response response = OkHttpManager.instance().getClient().newBuilder()
                    .proxy(java.net.Proxy.NO_PROXY).build().newCall(request).execute();
            ResponseBody body = response.body();
            return body != null ? body.string() : null;
        } catch (Exception e) {
            Log.e(TAG, "Can't fetch playlist directly %s: %s", playlistUrl, e.getMessage());
            return null;
        }
    }

    /**
     * Parse the raw config: a JSON multi-source array or a single playlist url (legacy).
     */
    private List<Video> parseConfig(String config) {
        if (config == null) {
            return new ArrayList<>();
        }

        String trimmed = config.trim();

        // Legacy: a single m3u/m3u8 playlist url
        if (!trimmed.startsWith("[")) {
            return parseM3U(fetchPlaylist(trimmed));
        }

        List<Video> result = new ArrayList<>();

        try {
            JSONArray sources = new JSONArray(trimmed);
            for (int i = 0; i < sources.length(); i++) {
                JSONObject source = sources.optJSONObject(i);
                if (source == null) {
                    continue;
                }

                String type = source.optString("type");
                if ("m3u8".equals(type) && source.has("group")) {
                    // Manually defined groups
                    result.addAll(parseManualSource(source.optJSONArray("group")));
                } else {
                    // "m3u" (or anything with a url): remote playlist, auto grouped by group-title
                    String url = source.optString("url", null);
                    if (url != null && !url.isEmpty()) {
                        result.addAll(parseM3U(fetchPlaylist(url)));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Can't parse live tv config, fallback to single url: %s", e.getMessage());
            return parseM3U(fetchPlaylist(trimmed));
        }

        return result;
    }

    /**
     * Parse a manually defined group array: [ { name, url: [ { name, url } ] } ]
     */
    private List<Video> parseManualSource(JSONArray groups) {
        List<Video> result = new ArrayList<>();

        if (groups == null) {
            return result;
        }

        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) {
                continue;
            }

            String groupName = group.optString("name", null);
            JSONArray channels = group.optJSONArray("url");
            if (channels == null) {
                continue;
            }

            for (int j = 0; j < channels.length(); j++) {
                JSONObject ch = channels.optJSONObject(j);
                if (ch == null) {
                    continue;
                }

                String streamUrl = ch.optString("url", null);
                if (streamUrl == null || streamUrl.isEmpty()) {
                    continue;
                }

                Video channel = new Video();
                channel.videoId = VIDEO_ID_PREFIX + streamUrl;
                channel.title = ch.optString("name", streamUrl);
                channel.secondTitle = groupName;
                channel.category = groupName;
                channel.cardImageUrl = ch.optString("logo", null);
                channel.bgImageUrl = channel.cardImageUrl;
                channel.isLive = true;
                result.add(channel);
            }
        }

        return result;
    }

    private List<Video> parseM3U(String content) {
        List<Video> result = new ArrayList<>();

        if (content == null) {
            return result;
        }

        String title = null;
        String logo = null;
        String group = null;

        for (String line : content.split("\r?\n")) {
            line = line.trim();

            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("#EXTINF")) {
                logo = null;
                group = null;

                Matcher matcher = ATTR_PATTERN.matcher(line);
                while (matcher.find()) {
                    String key = matcher.group(1);
                    String value = matcher.group(2);
                    if ("tvg-logo".equals(key)) {
                        logo = value;
                    } else if ("group-title".equals(key)) {
                        group = value;
                    }
                }

                title = extractTitle(line);
            } else if (line.startsWith("#EXTGRP:")) {
                // Group directive that applies to the following (current) entry
                group = line.substring("#EXTGRP:".length()).trim();
            } else if (line.startsWith("#")) {
                // skip other directives (#EXTM3U, #EXTVLCOPT etc)
            } else if (title != null) {
                // Stream url line. Cut pipe suffixes (|User-Agent=...) unsupported by the player.
                int pipeIdx = line.indexOf('|');
                String streamUrl = pipeIdx != -1 ? line.substring(0, pipeIdx) : line;

                Video channel = new Video();
                channel.videoId = VIDEO_ID_PREFIX + streamUrl;
                channel.title = title;
                channel.secondTitle = group;
                channel.category = group;
                channel.cardImageUrl = logo;
                channel.bgImageUrl = logo;
                channel.isLive = true;
                result.add(channel);

                title = null;
                logo = null;
                group = null;
            }
        }

        return result;
    }

    /**
     * Channel name is placed after the last comma that follows the quoted attributes.
     */
    private String extractTitle(String extInf) {
        int lastQuote = extInf.lastIndexOf('"');
        int comma = extInf.indexOf(',', lastQuote != -1 ? lastQuote : 0);
        return comma != -1 ? extInf.substring(comma + 1).trim() : null;
    }

    /**
     * A named group of channels rendered as a single horizontal row.
     */
    public static class ChannelGroup {
        public final String title;
        public final List<Video> channels = new ArrayList<>();

        public ChannelGroup(String title) {
            this.title = title;
        }
    }
}
