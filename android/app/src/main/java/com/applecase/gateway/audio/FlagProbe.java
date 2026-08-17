package com.applecase.gateway.audio;

import android.media.AudioAttributes;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * INCALL_MUSIC 标志位探测器(root,通话中运行)。
 * 用法: FlagProbe <flagHex> <秒数>
 * 创建 AudioTrack(USAGE_VOICE_COMMUNICATION + 指定 flag)持续写 440Hz;
 * 外部用 dumpsys media.audio_flinger 观察 INCALL_MUSIC 输出线程(I/O 37)的
 * Frames written 是否增长,以此确定本 ROM 正确的 FLAG_INCALL_MUSIC 位值。
 */
public class FlagProbe {

    public static void main(String[] args) throws Exception {
        int flag = args.length > 0 ? (int) Long.parseLong(args[0], 16) : 0x800;
        int secs = args.length > 1 ? Integer.parseInt(args[1]) : 8;

        android.util.Log.i("AC.Probe", "flag=0x" + Integer.toHexString(flag) + " secs=" + secs);

        AudioAttributes.Builder ab = new AudioAttributes.Builder()
                .setUsage(args.length > 3 ? AudioAttributes.USAGE_VOICE_COMMUNICATION
                                          : AudioAttributes.USAGE_MEDIA)   // INCALL_MUSIC 只在 MEDIA 策略生效(AOSP)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setFlags(flag);
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        AudioTrack t = new AudioTrack(ab.build(), fmt, 640 * 16,
                AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        android.util.Log.i("AC.Probe", "track state=" + t.getState());

        if (args.length > 2 && "prefer".equals(args[2])) {
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object thread = at.getMethod("systemMain").invoke(null);
                Object ctx = at.getMethod("getSystemContext").invoke(thread);
                AudioManager am = (AudioManager) ctx.getClass()
                        .getMethod("getSystemService", String.class).invoke(ctx, Context.AUDIO_SERVICE);
                if (am == null) { am = ((android.content.Context) ctx).getSystemService(AudioManager.class); }
                AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                for (AudioDeviceInfo d : devs) {
                    android.util.Log.i("AC.Probe", "dev id=" + d.getId() + " type=" + d.getType() + " name=" + d.getProductName());
                    if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY) {   // Telephony(Tx) 端口
                        boolean ok = t.setPreferredDevice(d);
                        android.util.Log.i("AC.Probe", "setPreferredDevice id=" + d.getId() + " ok=" + ok);
                        break;
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("AC.Probe", "prefer failed: " + e);
            }
        }
        t.play();

        byte[] tone = new byte[640];
        for (int i = 0; i < 320; i++) {
            short v = (short) (12000 * Math.sin(2 * Math.PI * 440 * i / 16000.0));
            tone[i * 2] = (byte) v;
            tone[i * 2 + 1] = (byte) (v >> 8);
        }
        long end = System.currentTimeMillis() + secs * 1000L;
        long written = 0;
        while (System.currentTimeMillis() < end) {
            int n = t.write(tone, 0, tone.length, AudioTrack.WRITE_BLOCKING);
            if (n > 0) written += n;
            Thread.sleep(18);
        }
        android.util.Log.i("AC.Probe", "done writtenBytes=" + written
                + " underruns=" + t.getUnderrunCount());
        t.stop();
        t.release();
    }
}
