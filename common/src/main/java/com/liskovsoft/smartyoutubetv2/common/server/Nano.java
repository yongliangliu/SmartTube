package com.liskovsoft.smartyoutubetv2.common.server;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.server.process.Action;
import com.liskovsoft.smartyoutubetv2.common.server.process.Process;
import com.liskovsoft.smartyoutubetv2.common.server.process.Setting;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * MOD: web config server (TV-New style). Routes /action and /settings,
 * everything else is served from the assets/webconfig folder.
 */
public class Nano extends NanoHTTPD {
    private static final String ASSETS_DIR = "webconfig/";
    private final Context mContext;
    private List<Process> mProcess;

    public Nano(Context context, int port) {
        super(port);
        mContext = context;
        addProcess();
    }

    private void addProcess() {
        mProcess = new ArrayList<>();
        mProcess.add(new Action(mContext));
        mProcess.add(new Setting(mContext));
    }

    public static Response success() {
        return success("OK");
    }

    public static Response success(String text) {
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, text);
    }

    public static Response json(String text) {
        return newFixedLengthResponse(Response.Status.OK, "application/json", text);
    }

    public static Response error(String text) {
        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, text);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String url = session.getUri().trim();
        Map<String, String> files = new HashMap<>();
        if (session.getMethod() == Method.POST) parse(session, files);
        if (url.contains("?")) url = url.substring(0, url.indexOf('?'));
        for (Process process : mProcess) if (process.isRequest(session, url)) return process.doResponse(session, url, files);
        return getAssets(url.substring(1));
    }

    private void parse(IHTTPSession session, Map<String, String> files) {
        try {
            session.parseBody(files);
        } catch (Exception ignored) {
        }
    }

    private Response getAssets(String path) {
        try {
            if (path.isEmpty()) path = "index.html";
            InputStream is = mContext.getAssets().open(ASSETS_DIR + path);
            return newFixedLengthResponse(Response.Status.OK, getMimeTypeForFile(path), is, is.available());
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_HTML, null);
        }
    }
}
