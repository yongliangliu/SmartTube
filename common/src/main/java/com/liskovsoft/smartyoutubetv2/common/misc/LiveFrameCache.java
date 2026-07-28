package com.liskovsoft.smartyoutubetv2.common.misc;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * MOD: TV Live (IPTV) first-frame preview cache.<br/>
 * Grabs a single frame from an m3u8/direct stream url with {@link MediaMetadataRetriever}
 * on a background thread and caches it on disk to be used as the channel card image.
 * Failures (many live HLS streams don't allow frame extraction) fall back to the caller's placeholder.
 */
public class LiveFrameCache {
    private static final String TAG = LiveFrameCache.class.getSimpleName();
    private static final String CACHE_DIR = "live_frames";
    private static final int JPEG_QUALITY = 80;
    @SuppressLint("StaticFieldLeak")
    private static LiveFrameCache sInstance;
    private final Context mContext;
    private final ExecutorService mExecutor;
    private final Set<String> mInProgress = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public interface Callback {
        void onFrame(File file);
    }

    private LiveFrameCache(Context context) {
        mContext = context.getApplicationContext();
        mExecutor = Executors.newFixedThreadPool(2, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "LiveFrameCache");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            }
        });
    }

    public static LiveFrameCache instance(Context context) {
        if (sInstance == null) {
            sInstance = new LiveFrameCache(context.getApplicationContext());
        }

        return sInstance;
    }

    /**
     * Returns the cached frame file for the given stream url, or null if not captured yet.
     */
    public File getCachedFile(String streamUrl) {
        if (streamUrl == null || streamUrl.isEmpty()) {
            return null;
        }

        File file = fileFor(streamUrl);
        return file.exists() && file.length() > 0 ? file : null;
    }

    /**
     * Grabs a frame (or returns the cached one). The callback is always invoked on the main thread.
     */
    public void capture(String streamUrl, Callback callback) {
        if (streamUrl == null || streamUrl.isEmpty()) {
            callback.onFrame(null);
            return;
        }

        File cached = getCachedFile(streamUrl);
        if (cached != null) {
            callback.onFrame(cached);
            return;
        }

        // Already being captured by another card: skip the heavy work, the cache will be filled soon.
        if (!mInProgress.add(streamUrl)) {
            callback.onFrame(null);
            return;
        }

        mExecutor.execute(() -> {
            File result = grabFrame(streamUrl);
            mInProgress.remove(streamUrl);
            Utils.post(() -> callback.onFrame(result));
        });
    }

    private File grabFrame(String streamUrl) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        try {
            retriever.setDataSource(streamUrl, new HashMap<>());
            Bitmap bitmap = retriever.getFrameAtTime();

            if (bitmap == null) {
                return null;
            }

            File file = fileFor(streamUrl);
            File tmp = new File(file.getAbsolutePath() + ".tmp");

            try (FileOutputStream out = new FileOutputStream(tmp)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            }
            bitmap.recycle();

            return tmp.renameTo(file) ? file : null;
        } catch (Throwable e) { // MediaMetadataRetriever may throw RuntimeException/IllegalArgumentException
            Log.e(TAG, "Can't grab frame for %s: %s", streamUrl, e.getMessage());
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    private File fileFor(String streamUrl) {
        File dir = new File(mContext.getCacheDir(), CACHE_DIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }

        return new File(dir, Integer.toHexString(streamUrl.hashCode()) + ".jpg");
    }
}
