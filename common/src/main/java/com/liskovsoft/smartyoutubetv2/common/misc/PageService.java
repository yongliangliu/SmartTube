package com.liskovsoft.smartyoutubetv2.common.misc;

import android.annotation.SuppressLint;
import android.content.Context;

import com.liskovsoft.sharedutils.mylogger.Log;
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

import io.reactivex.Observable;

/**
 * MOD: Page section backend.<br/>
 * Reads the {@code page} section of {@link LiveTvData} (a manually defined group array,
 * same shape as the TV Live manual groups) and converts each leaf entry into a {@link Video}
 * card. Selecting a card opens the target url in the built-in {@code WebBrowser} full-screen
 * WebView (with a virtual remote cursor), instead of the internal player.
 * <pre>
 * [
 *   { "name": "Tools", "url": [ { "name": "Baidu", "url": "https://www.baidu.com", "logo": "..." } ] }
 * ]
 * </pre>
 */
public class PageService {
    private static final String TAG = PageService.class.getSimpleName();
    /**
     * Sidebar section id. NOTE: keep below 30 (built-in range) and distinct from
     * {@link LiveTvService#SECTION_ID} (23).
     */
    public static final int SECTION_ID = 24;
    private static final String VIDEO_ID_PREFIX = "page:";
    @SuppressLint("StaticFieldLeak")
    private static PageService sInstance;
    private final Context mContext;
    private List<Video> mCachedPages;
    private String mCachedConfig;

    private PageService(Context context) {
        mContext = context.getApplicationContext();
    }

    public static PageService instance(Context context) {
        if (sInstance == null) {
            sInstance = new PageService(context.getApplicationContext());
        }

        return sInstance;
    }

    public static boolean isPage(Video video) {
        return video != null && video.videoId != null && video.videoId.startsWith(VIDEO_ID_PREFIX);
    }

    public static String getPageUrl(Video video) {
        return isPage(video) ? video.videoId.substring(VIDEO_ID_PREFIX.length()) : null;
    }

    public Observable<List<PageGroup>> getPageGroupsObserve() {
        return RxHelper.fromCallable(this::getPageGroups);
    }

    /**
     * Blocking call. Run on a worker thread.
     */
    public List<Video> getPages() {
        String config = LiveTvData.instance(mContext).getPageConfig();

        if (mCachedPages != null && config.equals(mCachedConfig)) {
            return mCachedPages;
        }

        List<Video> pages = parseConfig(config);

        mCachedPages = pages;
        mCachedConfig = config;

        return pages;
    }

    public List<PageGroup> getPageGroups() {
        return groupPages(getPages());
    }

    public List<Video> getCachedPages() {
        return mCachedPages != null ? mCachedPages : new ArrayList<>();
    }

    public List<PageGroup> groupPages(List<Video> pages) {
        Map<String, PageGroup> groups = new LinkedHashMap<>();
        String fallback = mContext.getString(R.string.header_web_page);

        for (Video page : pages) {
            String name = page.category != null && !page.category.isEmpty() ? page.category : fallback;
            PageGroup group = groups.get(name);
            if (group == null) {
                group = new PageGroup(name);
                groups.put(name, group);
            }
            group.pages.add(page);
        }

        return new ArrayList<>(groups.values());
    }

    private List<Video> parseConfig(String config) {
        List<Video> result = new ArrayList<>();

        if (config == null) {
            return result;
        }

        String trimmed = config.trim();
        if (!trimmed.startsWith("[")) {
            return result;
        }

        try {
            result.addAll(parseGroups(new JSONArray(trimmed)));
        } catch (Exception e) {
            Log.e(TAG, "Can't parse page config: %s", e.getMessage());
        }

        return result;
    }

    /**
     * Parse a group array: [ { name, url: [ { name, url, logo } ] } ]
     */
    private List<Video> parseGroups(JSONArray groups) {
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
            JSONArray items = group.optJSONArray("url");
            if (items == null) {
                continue;
            }

            for (int j = 0; j < items.length(); j++) {
                JSONObject item = items.optJSONObject(j);
                if (item == null) {
                    continue;
                }

                String pageUrl = item.optString("url", null);
                if (pageUrl == null || pageUrl.isEmpty()) {
                    continue;
                }

                Video page = new Video();
                page.videoId = VIDEO_ID_PREFIX + pageUrl;
                page.title = item.optString("name", pageUrl);
                page.secondTitle = groupName;
                page.category = groupName;
                page.cardImageUrl = item.optString("logo", null);
                page.bgImageUrl = page.cardImageUrl;
                result.add(page);
            }
        }

        return result;
    }

    /**
     * A named group of pages rendered as a single horizontal row.
     */
    public static class PageGroup {
        public final String title;
        public final List<Video> pages = new ArrayList<>();

        public PageGroup(String title) {
            this.title = title;
        }
    }
}
