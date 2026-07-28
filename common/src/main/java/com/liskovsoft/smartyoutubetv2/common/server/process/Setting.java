package com.liskovsoft.smartyoutubetv2.common.server.process;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.LiveTvData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.prefs.WordLookupData;
import com.liskovsoft.smartyoutubetv2.common.proxy.Proxy;
import com.liskovsoft.smartyoutubetv2.common.proxy.ProxyManager;
import com.liskovsoft.smartyoutubetv2.common.server.Nano;

import org.json.JSONObject;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * MOD: /settings route: returns the current config as JSON (form prefill).
 */
public class Setting implements Process {
    private final Context mContext;

    public Setting(Context context) {
        mContext = context;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String path) {
        return "/settings".equals(path);
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String path, Map<String, String> files) {
        WordLookupData lookupData = WordLookupData.instance(mContext);
        PlayerTweaksData tweaksData = PlayerTweaksData.instance(mContext);

        try {
            JSONObject json = new JSONObject();
            json.put("lookup_enabled", tweaksData.isSubtitleWordLookupEnabled());
            json.put("endpoint", lookupData.getEndpoint());
            json.put("auth_token", lookupData.getAuthToken());
            json.put("kv_base", lookupData.getKvBase());
            json.put("kv_token", lookupData.getKvToken());
            json.put("live_tv_config", LiveTvData.instance(mContext).getLiveConfig());
            json.put("page_config", LiveTvData.instance(mContext).getPageConfig());

            // MOD: web proxy config (enable switch + details)
            ProxyManager proxyManager = new ProxyManager(mContext);
            json.put("proxy_enabled", GeneralData.instance(mContext).isProxyEnabled());
            if (proxyManager.isProxySupported()) {
                json.put("proxy_type", proxyManager.getProxyType() == Proxy.Type.SOCKS ? "socks" : "http");
                json.put("proxy_host", proxyManager.getProxyHost());
                int proxyPort = proxyManager.getProxyPort();
                json.put("proxy_port", proxyPort > 0 ? String.valueOf(proxyPort) : "");
                json.put("proxy_username", proxyManager.getProxyUsername());
                json.put("proxy_password", proxyManager.getProxyPassword());
            }

            return Nano.json(json.toString());
        } catch (Exception e) {
            return Nano.error(e.getMessage());
        }
    }
}
