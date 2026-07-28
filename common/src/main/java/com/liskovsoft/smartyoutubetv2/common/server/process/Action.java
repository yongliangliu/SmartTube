package com.liskovsoft.smartyoutubetv2.common.server.process;

import android.content.Context;
import android.text.TextUtils;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.misc.LiveProxyBypass;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.LiveTvData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.prefs.WordLookupData;
import com.liskovsoft.smartyoutubetv2.common.proxy.PasswdInetSocketAddress;
import com.liskovsoft.smartyoutubetv2.common.proxy.Proxy;
import com.liskovsoft.smartyoutubetv2.common.proxy.ProxyManager;
import com.liskovsoft.smartyoutubetv2.common.server.Nano;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * MOD: /action route (TV-New style). do=setting saves the word lookup config.
 */
public class Action implements Process {
    private final Context mContext;

    public Action(Context context) {
        mContext = context;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String path) {
        return "/action".equals(path);
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String path, Map<String, String> files) {
        Map<String, String> params = session.getParms();
        String param = params.get("do");
        if ("setting".equals(param)) onSetting(params);
        return Nano.success();
    }

    private void onSetting(Map<String, String> params) {
        WordLookupData lookupData = WordLookupData.instance(mContext);
        PlayerTweaksData tweaksData = PlayerTweaksData.instance(mContext);

        if (params.containsKey("lookup_enabled")) {
            tweaksData.setSubtitleWordLookupEnabled("true".equals(params.get("lookup_enabled")));
            tweaksData.persistNow();
        }
        if (params.containsKey("endpoint")) {
            lookupData.setEndpoint(params.get("endpoint"));
        }
        if (params.containsKey("auth_token")) {
            lookupData.setAuthToken(params.get("auth_token"));
        }
        if (params.containsKey("kv_base")) {
            lookupData.setKvBase(params.get("kv_base"));
        }
        if (params.containsKey("kv_token")) {
            lookupData.setKvToken(params.get("kv_token"));
        }
        lookupData.persistNow();

        if (params.containsKey("live_tv_config")) {
            LiveTvData liveTvData = LiveTvData.instance(mContext);
            liveTvData.setConfig(buildCombinedConfig(params.get("live_tv_config"), params.get("page_config")));
            liveTvData.persistNow();
        } else if (params.containsKey("live_tv_url")) { // MOD: backward compatible with the old single-url field
            LiveTvData liveTvData = LiveTvData.instance(mContext);
            liveTvData.setConfig(params.get("live_tv_url"));
            liveTvData.persistNow();
        }

        if (params.containsKey("proxy_enabled")) {
            applyProxy(params);
        }
    }

    // MOD: merge the live and page sections into a single { "m3u8": ..., "page": ... } config.
    // When no page config is present, keep the plain/legacy live format for backward compat.
    private String buildCombinedConfig(String live, String page) {
        if (page == null || page.trim().isEmpty()) {
            return live;
        }
        try {
            org.json.JSONObject obj = new org.json.JSONObject();
            obj.put("m3u8", toJsonValue(live));
            obj.put("page", toJsonValue(page));
            return obj.toString();
        } catch (Exception e) {
            return live;
        }
    }

    // A bare url stays a string; a JSON array/object is stored as structured JSON.
    private Object toJsonValue(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("[")) {
            try {
                return new org.json.JSONArray(trimmed);
            } catch (Exception ignored) {
            }
        } else if (trimmed.startsWith("{")) {
            try {
                return new org.json.JSONObject(trimmed);
            } catch (Exception ignored) {
            }
        }
        return value;
    }

    // MOD: web proxy config from the web page. Mirrors GeneralSettingsPresenter#appendProxyManager.
    private void applyProxy(Map<String, String> params) {
        ProxyManager proxyManager = new ProxyManager(mContext);
        if (!proxyManager.isProxySupported()) {
            return;
        }

        boolean enabled = "true".equals(params.get("proxy_enabled"));

        try {
            if (enabled) {
                String host = params.get("proxy_host");
                int port = Helpers.parseInt(params.get("proxy_port"));
                if (TextUtils.isEmpty(host) || port <= 0) {
                    return; // invalid details: keep current proxy untouched
                }
                Proxy.Type type = "socks".equalsIgnoreCase(params.get("proxy_type")) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
                Proxy proxy = new Proxy(type, PasswdInetSocketAddress.createUnresolved(
                        host, port, params.get("proxy_username"), params.get("proxy_password")));
                proxyManager.saveProxyInfoToPrefs(proxy, true);
            } else {
                proxyManager.saveProxyInfoToPrefs(null, false);
            }

            proxyManager.configureSystemProxy();
            GeneralData.instance(mContext).setProxyEnabled(enabled);
            // Proxy with authentication is supported only by OkHttp (same as the on-device settings)
            PlayerTweaksData.instance(mContext).setPlayerDataSource(
                    enabled ? PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP : PlayerTweaksData.PLAYER_DATA_SOURCE_CRONET);
            if (enabled) {
                LiveProxyBypass.install();
            }
            OkHttpManager.unhold();
        } catch (IllegalArgumentException e) {
            // invalid host/port: ignore
        }
    }
}
