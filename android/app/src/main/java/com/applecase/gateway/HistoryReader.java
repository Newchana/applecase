package com.applecase.gateway;

import android.content.Context;
import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 读取系统短信库(content://sms)与通话记录(content://call_log),
 * Web 页面呈现与手机自带 App 一致的完整历史。
 *
 * 说明:
 * - 入信/本机短信 App 发出的都在系统库里;本网关 App 发出的短信不会写库
 *   (需默认短信 App 角色),与 HistoryStore 里自记的出信按 (时间±3s, 内容) 去重合并。
 * - 通话记录系统库全量覆盖(所有来去电),直接以它为准。
 */
public final class HistoryReader {

    private HistoryReader() {}

    /**
     * 组装 HISTORY 数据。
     * @param slotResolver subId → 卡槽(SIM 卡相关记录用;查不到返回 0)
     */
    public static JSONObject readAll(Context ctx, java.util.function.IntUnaryOperator slotResolver) {
        JSONObject out = new JSONObject();
        try { out.put("sms", readSms(ctx, slotResolver)); } catch (Exception ignored) {}
        try { out.put("calls", readCalls(ctx, slotResolver)); } catch (Exception ignored) {}
        return out;
    }

    /** 系统短信 + 本网关自发短信合并(时间倒序)。 */
    private static JSONArray readSms(Context ctx, java.util.function.IntUnaryOperator slotResolver) {
        // 1) 系统库
        JSONArray merged = new JSONArray();
        java.util.List<long[]> storeOutTs = new java.util.ArrayList<>(); // 供去重的占位
        try (Cursor c = ctx.getContentResolver().query(
                android.provider.Telephony.Sms.CONTENT_URI,
                new String[]{"address", "body", "date", "type"},
                null, null, "date DESC")) {
            if (c != null) {
                while (c.moveToNext() && merged.length() < 400) {
                    String addr = safe(c.getString(0));
                    String body = safe(c.getString(1));
                    long date = c.getLong(2);
                    int type = c.getInt(3);
                    JSONObject e = new JSONObject();
                    e.put("dir", type == 2 ? "out" : "in");   // 2=发出,其余按收
                    e.put("addr", addr.isEmpty() ? "?" : addr);
                    e.put("body", body);
                    e.put("ts", date);
                    e.put("slot", 0);   // 本机 ROM 短信表无 sub 列,不区分卡
                    merged.put(e);
                }
            }
        } catch (Exception e) {
            android.util.Log.w("AC.Hist", "read sms db failed: " + e);
        }

        // 2) 网关自发(HistoryStore 的 out 记录,系统库没有)——时间临近且内容相同视为重复
        JSONObject store = HistoryStore.read(ctx);
        JSONArray sms = store.optJSONArray("sms");
        if (sms != null) {
            for (int i = 0; i < sms.length(); i++) {
                JSONObject e = sms.optJSONObject(i);
                if (e == null || !"out".equals(e.optString("dir"))) continue;
                if (existsSimilar(merged, e)) continue;
                merged.put(e);
            }
        }

        // 3) 统一按时间倒序
        JSONArray sorted = new JSONArray();
        for (int i = 0; i < merged.length(); i++) sorted.put(merged.optJSONObject(i));
        try {
            java.util.List<JSONObject> l = new java.util.ArrayList<>();
            for (int i = 0; i < sorted.length(); i++) l.add(sorted.getJSONObject(i));
            l.sort((a, b) -> Long.compare(b.optLong("ts"), a.optLong("ts")));
            JSONArray r = new JSONArray();
            for (JSONObject o : l) r.put(o);
            return r;
        } catch (Exception e) {
            return sorted;
        }
    }

    /** 系统通话记录 → {slot,addr,dir,state,ts,dur}(时间倒序,最近 60 条)。
     *  带 subscription_id 查(标卡);ROM 不支持该列时降级为不带。 */
    private static JSONArray readCalls(Context ctx, java.util.function.IntUnaryOperator slotResolver) {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        boolean hasSub = true;
        try {
            c = ctx.getContentResolver().query(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    new String[]{"number", "date", "duration", "type", "subscription_id"},
                    null, null, "date DESC");
        } catch (Exception e) {
            hasSub = false;
        }
        if (c == null) {
            hasSub = false;
            try {
                c = ctx.getContentResolver().query(
                        android.provider.CallLog.Calls.CONTENT_URI,
                        new String[]{"number", "date", "duration", "type"},
                        null, null, "date DESC");
            } catch (Exception e2) {
                android.util.Log.w("AC.Hist", "read call_log failed: " + e2);
                return arr;
            }
        }
        try {
            while (c.moveToNext() && arr.length() < 60) {
                String num = safe(c.getString(0));
                long date = c.getLong(1);
                long dur = c.getLong(2);
                int type = c.getInt(3);
                int slot = 0;
                if (hasSub) {
                    int subId = c.getInt(4);
                    int sl = slotResolver.applyAsInt(subId);
                    slot = sl < 0 ? 0 : sl;
                }
                String dir, state;
                if (type == android.provider.CallLog.Calls.OUTGOING_TYPE) { dir = "out"; state = "ended"; }
                else if (type == android.provider.CallLog.Calls.MISSED_TYPE) { dir = "in"; state = "missed"; }
                else { dir = "in"; state = "ended"; }
                JSONObject e = new JSONObject();
                e.put("addr", num.isEmpty() ? "?" : num);
                e.put("dir", dir).put("state", state)
                 .put("ts", date).put("dur", dur).put("slot", slot);
                arr.put(e);
            }
        } catch (Exception ignored) {
        } finally {
            try { c.close(); } catch (Exception ignored) {}
        }
        return arr;
    }

    private static boolean existsSimilar(JSONArray db, JSONObject e) {
        long ts = e.optLong("ts");
        String body = e.optString("body");
        for (int i = 0; i < db.length(); i++) {
            JSONObject d = db.optJSONObject(i);
            if (d == null) continue;
            if (Math.abs(d.optLong("ts") - ts) < 3000 && d.optString("body").equals(body)) return true;
        }
        return false;
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
