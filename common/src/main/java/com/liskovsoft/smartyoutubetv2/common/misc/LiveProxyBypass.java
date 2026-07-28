package com.liskovsoft.smartyoutubetv2.common.misc;

import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MOD: TV Live (IPTV) proxy bypass.<br/>
 * When the global web proxy is enabled and a live stream fails to load, the stream's host
 * gets marked (runtime only, nothing is persisted) and all following connections to that host
 * go direct (NO_PROXY). Raises the connectivity rate for streams unreachable through the proxy.
 */
public class LiveProxyBypass {
    private static final String TAG = LiveProxyBypass.class.getSimpleName();
    private static final Set<String> sDirectHosts = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static boolean sInstalled;

    private LiveProxyBypass() {
    }

    /**
     * Wraps the default {@link ProxySelector} with a per-host direct fallback. Idempotent.<br/>
     * The android default selector reads the proxy system properties on every select() call,
     * so it's safe to install the wrapper at any time.
     */
    public static synchronized void install() {
        if (sInstalled) {
            return;
        }

        final ProxySelector delegate = ProxySelector.getDefault();

        if (delegate == null) {
            return;
        }

        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                if (uri != null && uri.getHost() != null && sDirectHosts.contains(uri.getHost())) {
                    return Collections.singletonList(Proxy.NO_PROXY);
                }
                return delegate.select(uri);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                delegate.connectFailed(uri, sa, ioe);
            }
        });

        sInstalled = true;
    }

    /**
     * Whether a web proxy is really configured (system properties applied by ProxyManager).
     */
    public static boolean isProxyActive() {
        return System.getProperty("http.proxyHost") != null || System.getProperty("socksProxyHost") != null;
    }

    /**
     * Marks the failed stream's host for direct connections.
     * @return true if newly marked and a retry makes sense,
     *         false otherwise (no proxy configured, bad url or the direct try already failed)
     */
    public static boolean markFailed(String streamUrl) {
        if (streamUrl == null || !isProxyActive()) {
            return false;
        }

        String host = null;
        try {
            host = URI.create(streamUrl).getHost();
        } catch (IllegalArgumentException e) {
            // malformed url
        }

        if (host == null) {
            return false;
        }

        install(); // make sure the selector is in place

        boolean added = sDirectHosts.add(host);

        if (added) {
            Log.d(TAG, "Live stream failed via proxy, switching the host to direct: " + host);
        }

        return added;
    }
}
