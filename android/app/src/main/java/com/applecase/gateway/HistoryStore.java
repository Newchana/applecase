package com.applecase.gateway;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 短信/通话历史(JSON 文件持久化,filesDir/history.json)。
 * Web 客户端打开页面时通过 GET_STATE → HISTORY 拉取,解决
 * "页面没开着时来的短信/电话看不到"的问题。
 */
public final class HistoryStore {

    private static final String FILE = "history.json";
    private static final int MAX_SMS = 100;
    private static final int MAX_CALLS = 50;

    private HistoryStore() {}

    /** 追加一条短信记录:{dir:"in"|"out", slot, addr, body, ts}。 */
    public static synchronized void addSms(Context ctx, String dir, int slot,
                                           String addr, String body, long ts) {
        JSONObject j = load(ctx);
        JSONArray arr = j.optJSONArray("sms");
        if (arr == null) { arr = new JSONArray(); try { j.put("sms", arr); } catch (Exception ignored) {} }
        try {
            JSONObject e = new JSONObject();
            e.put("dir", dir).put("slot", slot).put("addr", addr)
             .put("body", body).put("ts", ts);
            arr.put(e);
            trim(arr, MAX_SMS);
            save(ctx, j);
        } catch (Exception ignored) {}
    }

    /** 追加一条已结束的通话记录:{slot, addr, dir:"in"|"out", state:"ended"|"missed", ts, dur}。 */
    public static synchronized void addCall(Context ctx, int slot, String addr,
                                            String dir, String state, long ts, long dur) {
        JSONObject j = load(ctx);
        JSONArray arr = j.optJSONArray("calls");
        if (arr == null) { arr = new JSONArray(); try { j.put("calls", arr); } catch (Exception ignored) {} }
        try {
            JSONObject e = new JSONObject();
            e.put("slot", slot).put("addr", addr).put("dir", dir)
             .put("state", state).put("ts", ts).put("dur", dur);
            arr.put(e);
            trim(arr, MAX_CALLS);
            save(ctx, j);
        } catch (Exception ignored) {}
    }

    /** 全量读取 {sms:[], calls:[]}(无文件返回空结构)。 */
    public static synchronized JSONObject read(Context ctx) {
        return load(ctx);
    }

    private static void trim(JSONArray arr, int max) throws Exception {
        while (arr.length() > max) arr.remove(0);
    }

    private static JSONObject load(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[2048];
            int n;
            InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8);
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void save(Context ctx, JSONObject j) {
        try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), FILE))) {
            OutputStreamWriter w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            w.write(j.toString());
            w.flush();
        } catch (Exception ignored) {}
    }
}
