package com.rioikeda.newslisten.designsystem

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.rioikeda.newslisten.preferences.PreferencesStore
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

enum class DSFeedbackVocabulary { CORRECT, INCORRECT, ACHIEVEMENT, STREAK_UP, SWIPE_CONFIRM }

enum class FeedbackSound { CORRECT, STREAK_UP }

enum class FeedbackHaptic { LIGHT, SOFT, MEDIUM }

/** Android SDK 依存の音・触覚出力境界。JVM テストでは手書き Fake に差し替える。 */
interface FeedbackPlayer {
    fun playSound(sound: FeedbackSound)
    fun performHaptic(haptic: FeedbackHaptic)
}

/** 5語彙の設定反映・300ms 間引き・出力写像を一元化する純 Kotlin ロジック。 */
class DSFeedback(
    private val player: FeedbackPlayer,
    private val sfxEnabled: () -> Boolean,
    private val hapticsEnabled: () -> Boolean,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lastPlayedAt = mutableMapOf<DSFeedbackVocabulary, Long>()

    fun play(vocabulary: DSFeedbackVocabulary) {
        val now = nowMillis()
        val previous = lastPlayedAt[vocabulary]
        if (previous != null && now - previous < MINIMUM_INTERVAL_MS) return
        lastPlayedAt[vocabulary] = now

        if (hapticsEnabled()) player.performHaptic(vocabulary.haptic)
        if (sfxEnabled()) vocabulary.sound?.let(player::playSound)
    }

    private val DSFeedbackVocabulary.haptic: FeedbackHaptic
        get() = when (this) {
            DSFeedbackVocabulary.CORRECT,
            DSFeedbackVocabulary.STREAK_UP,
            DSFeedbackVocabulary.SWIPE_CONFIRM,
            -> FeedbackHaptic.LIGHT
            DSFeedbackVocabulary.INCORRECT -> FeedbackHaptic.SOFT
            DSFeedbackVocabulary.ACHIEVEMENT -> FeedbackHaptic.MEDIUM
        }

    private val DSFeedbackVocabulary.sound: FeedbackSound?
        get() = when (this) {
            DSFeedbackVocabulary.CORRECT -> FeedbackSound.CORRECT
            DSFeedbackVocabulary.STREAK_UP -> FeedbackSound.STREAK_UP
            else -> null
        }

    private companion object {
        const val MINIMUM_INTERVAL_MS = 300L
    }
}

/**
 * AudioTrack の短い合成音と View の触覚定数を使う端末実装。
 *
 * WHY AudioTrack: ADR-088 は SoundPool を想定するが、短い sine 波へエンベロープを掛けて都度生成
 * すれば音声アセットの同梱・読込失敗・端末密度差に依存しない。リンガーが NORMAL の時だけ鳴らす。
 */
class AndroidFeedbackPlayer(
    context: Context,
    private val view: View,
) : FeedbackPlayer {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun playSound(sound: FeedbackSound) {
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        // 性格差（ADR-088）: STREAK_UP は「微かな高揚感」のため CORRECT より高音にする。
        val frequency = if (sound == FeedbackSound.STREAK_UP) 880.0 else 660.0
        val samples = synthesizeTone(frequency)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(samples, 0, samples.size)
            track.setNotificationMarkerPosition(samples.size)
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(audioTrack: AudioTrack) = audioTrack.release()
                    override fun onPeriodicNotification(audioTrack: AudioTrack) = Unit
                }
            )
            track.play()
        } catch (_: Exception) {
            // 再生に失敗した場合は marker 経由の解放が走らないため、ここでリークを防ぐ。
            runCatching { track.release() }
        }
    }

    override fun performHaptic(haptic: FeedbackHaptic) {
        val constant = when (haptic) {
            FeedbackHaptic.LIGHT -> HapticFeedbackConstants.KEYBOARD_TAP
            FeedbackHaptic.SOFT -> HapticFeedbackConstants.VIRTUAL_KEY
            FeedbackHaptic.MEDIUM -> HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }

    /**
     * 紙・インク系の質感を狙った短音合成（ADR-088）。
     * 裸のサイン波は電子ビープに聞こえるため、急減衰エンベロープの純音に、
     * さらに速く減衰する簡易帯域制限ノイズ（紙を弾く成分）を重ねる。
     */
    private fun synthesizeTone(frequency: Double): ShortArray {
        val count = SAMPLE_RATE * DURATION_MS / 1_000
        val random = Random(SEED)
        var previousNoise = 0.0
        return ShortArray(count) { index ->
            val progress = index.toDouble() / count
            val toneEnvelope = exp(-5.0 * progress)
            val noiseEnvelope = exp(-18.0 * progress)
            val raw = random.nextDouble(-1.0, 1.0)
            // 直前サンプルと平均して高域を落とす（簡易ローパス）
            val noise = (raw + previousNoise) / 2.0
            previousNoise = raw
            val tone = sin(2.0 * PI * frequency * index / SAMPLE_RATE)
            ((tone * 0.65 * toneEnvelope + noise * 0.35 * noiseEnvelope) * Short.MAX_VALUE * 0.18)
                .toInt().toShort()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val DURATION_MS = 90

        /** ノイズ成分を毎回同じ質感にするための固定シード。 */
        const val SEED = 20260729
    }
}

/** Compose の現在 View を触覚境界へ渡し、DataStore 設定を常に参照する。 */
@Composable
fun rememberDSFeedback(preferencesStore: PreferencesStore): DSFeedback {
    val context = LocalContext.current
    val view = LocalView.current
    return remember(context, view, preferencesStore) {
        DSFeedback(
            player = AndroidFeedbackPlayer(context, view),
            sfxEnabled = { preferencesStore.sfxEnabled.value },
            hapticsEnabled = { preferencesStore.hapticsEnabled.value },
        )
    }
}
