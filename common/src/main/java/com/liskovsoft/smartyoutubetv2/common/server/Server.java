package com.liskovsoft.smartyoutubetv2.common.server;

import android.annotation.SuppressLint;
import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.prefs.LiveTvData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.prefs.WordLookupData;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * MOD: web config server (TV-New style).<br/>
 * Serves a config page on the LAN so key params (e.g. subtitle word lookup)
 * can be edited quickly from a phone browser.
 */
public class Server {
    @SuppressLint("StaticFieldLeak")
    private Context mContext;
    private Nano mNano;
    private int mPort;

    private static class Loader {
        static volatile Server INSTANCE = new Server();
    }

    public static Server get() {
        return Loader.INSTANCE;
    }

    public Server() {
        mPort = 9978;
    }

    public int getPort() {
        return mPort;
    }

    public String getAddress() {
        return "http://" + getIp() + ":" + getPort();
    }

    public void start(Context context) {
        if (mNano != null) return;
        mContext = context.getApplicationContext();
        // Pre-warm the prefs singletons on the main thread (HTTP requests come from worker threads)
        WordLookupData.instance(mContext);
        PlayerTweaksData.instance(mContext);
        LiveTvData.instance(mContext);
        do {
            try {
                mNano = new Nano(mContext, mPort);
                mNano.start();
                break;
            } catch (Exception e) {
                ++mPort;
                mNano.stop();
                mNano = null;
            }
        } while (mPort < 9999);
    }

    public void stop() {
        if (mNano != null) mNano.stop();
        mNano = null;
    }

    private static String getIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }
}
