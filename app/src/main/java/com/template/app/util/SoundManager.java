package com.template.app.util;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 音效封装：用 {@link AudioTrack} 直接播放由代码<b>实时合成</b>的 PCM 提示音（不打包任何音频资源）。
 * <p>
 * 相比 {@link android.media.SoundPool}，AudioTrack 直推 PCM 无需「临时文件 + 解码 + 异步加载回调」，
 * 在 HarmonyOS 上兼容性最好、最稳定。音量跟随系统<b>媒体</b>音量（与系统「触摸提示音」开关无关）。
 */
public final class SoundManager {

    private static final String TAG = "SoundManager";
    private static final int SAMPLE_RATE = 44100;

    private static final double CLICK_FREQ = 720.0;
    private static final double SUCCESS_FREQ = 990.0;
    private static final double ERROR_FREQ = 200.0;

    private static final int CLICK_MS = 90;
    private static final int SUCCESS_MS = 180;
    private static final int ERROR_MS = 200;

    private final Context appContext;
    private boolean enabled = true;
    private final ExecutorService player = Executors.newSingleThreadExecutor();

    private SoundManager(Context context) {
        appContext = context.getApplicationContext();
    }

    private static volatile SoundManager instance;

    public static SoundManager getInstance(Context context) {
        if (instance == null) {
            synchronized (SoundManager.class) {
                if (instance == null) {
                    instance = new SoundManager(context);
                }
            }
        }
        return instance;
    }

    /** 全局静音开关（预留给「设置页音效开关」）。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void click() {
        play(CLICK_FREQ, CLICK_MS);
    }

    public void success() {
        play(SUCCESS_FREQ, SUCCESS_MS);
    }

    public void error() {
        play(ERROR_FREQ, ERROR_MS);
    }

    /**
     * 合成一段带「渐入 + 指数衰减」包络的 16-bit 单声道 PCM，写入 AudioTrack 播放。
     * 在后台单线程执行，避免阻塞 UI。
     */
    private void play(final double freqHz, final int ms) {
        if (!enabled) {
            return;
        }
        player.execute(() -> {
            try {
                int numSamples = (int) (SAMPLE_RATE * ms / 1000.0);
                short[] buf = new short[numSamples];

                int attack = Math.max(1, numSamples / 12);   // 渐入，避免爆音
                double decay = 5.0;                          // 衰减常数
                double amp = 0.85;
                for (int i = 0; i < numSamples; i++) {
                    double t = (double) i / SAMPLE_RATE;
                    double env = Math.exp(-decay * t);
                    if (i < attack) {
                        env *= (double) i / attack;          // 线性渐入
                    }
                    double sample = Math.sin(2 * Math.PI * freqHz * t) * env * amp;
                    buf[i] = (short) (sample * 32767);
                }

                int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                AudioTrack track = new AudioTrack(
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build(),
                        new AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build(),
                        Math.max(minBuf, numSamples * 2),
                        AudioTrack.MODE_STATIC,
                        AudioManager.AUDIO_SESSION_ID_GENERATE);
                track.write(buf, 0, numSamples);
                track.play();

                // 播放完毕后释放
                try {
                    Thread.sleep(ms + 30);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop();
                }
                track.release();
            } catch (Exception e) {
                Log.e(TAG, "play failed: " + e.getMessage(), e);
            }
        });
    }
}
