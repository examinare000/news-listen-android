package com.rioikeda.newslisten.podcast

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 再生失敗の分類と、`PlayerController.state` の Fake 注入の準拠テスト。
 *
 * verifies: CI-T2a
 * 正本: docs/design/modules/android/2026-09-16-implementation-spec-playback-auth.md §3.1・§4 CI-T2a。
 * 分類の期待値は order の接頭辞規則（IO_FILE_NOT_FOUND / IO_BAD_HTTP_STATUS → Source、他の IO_* → Network、
 * DECODING_* / PARSING_* → Decode、他 → Unknown）から決める。DECODER_* は接頭辞が異なるため Unknown。
 * ExoPlayerController の Media3 イベント写像（CI-T2b）は JVM で検証できないためここでは扱わない。
 */
@Suppress("UnsafeOptInUsageError")
class PlaybackStateTest {

    private fun assertClassified(expected: PlaybackFailureReason, constants: Map<String, Int>) {
        constants.forEach { (name, code) ->
            assertEquals("ERROR_CODE_$name(=$code)", expected, classifyPlaybackError(code))
        }
    }

    @Test
    fun `T2a Source は FILE_NOT_FOUND と BAD_HTTP_STATUS`() {
        assertClassified(
            PlaybackFailureReason.Source,
            mapOf(
                "IO_BAD_HTTP_STATUS" to PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                "IO_FILE_NOT_FOUND" to PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            ),
        )
    }

    @Test
    fun `T2a Source 以外の IO 系は Network`() {
        assertClassified(
            PlaybackFailureReason.Network,
            mapOf(
                "IO_UNSPECIFIED" to PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                "IO_NETWORK_CONNECTION_FAILED" to PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                "IO_NETWORK_CONNECTION_TIMEOUT" to PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                "IO_INVALID_HTTP_CONTENT_TYPE" to PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                "IO_NO_PERMISSION" to PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                "IO_CLEARTEXT_NOT_PERMITTED" to PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
                "IO_READ_POSITION_OUT_OF_RANGE" to PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            ),
        )
    }

    @Test
    fun `T2a PARSING と DECODING 系は Decode`() {
        assertClassified(
            PlaybackFailureReason.Decode,
            mapOf(
                "PARSING_CONTAINER_MALFORMED" to PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                "PARSING_MANIFEST_MALFORMED" to PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                "PARSING_CONTAINER_UNSUPPORTED" to PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                "PARSING_MANIFEST_UNSUPPORTED" to PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                "DECODING_FAILED" to PlaybackException.ERROR_CODE_DECODING_FAILED,
                "DECODING_FORMAT_EXCEEDS_CAPABILITIES" to PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                "DECODING_FORMAT_UNSUPPORTED" to PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                "DECODING_RESOURCES_RECLAIMED" to PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            ),
        )
    }

    @Test
    fun `T2a 表外の定数は Unknown にコードを保持する`() {
        val unknownConstants = mapOf(
            "INVALID_STATE" to PlaybackException.ERROR_CODE_INVALID_STATE,
            "BAD_VALUE" to PlaybackException.ERROR_CODE_BAD_VALUE,
            "PERMISSION_DENIED" to PlaybackException.ERROR_CODE_PERMISSION_DENIED,
            "NOT_SUPPORTED" to PlaybackException.ERROR_CODE_NOT_SUPPORTED,
            "DISCONNECTED" to PlaybackException.ERROR_CODE_DISCONNECTED,
            "AUTHENTICATION_EXPIRED" to PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED,
            "PREMIUM_ACCOUNT_REQUIRED" to PlaybackException.ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED,
            "CONCURRENT_STREAM_LIMIT" to PlaybackException.ERROR_CODE_CONCURRENT_STREAM_LIMIT,
            "PARENTAL_CONTROL_RESTRICTED" to PlaybackException.ERROR_CODE_PARENTAL_CONTROL_RESTRICTED,
            "NOT_AVAILABLE_IN_REGION" to PlaybackException.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
            "SKIP_LIMIT_REACHED" to PlaybackException.ERROR_CODE_SKIP_LIMIT_REACHED,
            "SETUP_REQUIRED" to PlaybackException.ERROR_CODE_SETUP_REQUIRED,
            "END_OF_PLAYLIST" to PlaybackException.ERROR_CODE_END_OF_PLAYLIST,
            "CONTENT_ALREADY_PLAYING" to PlaybackException.ERROR_CODE_CONTENT_ALREADY_PLAYING,
            "UNSPECIFIED" to PlaybackException.ERROR_CODE_UNSPECIFIED,
            "REMOTE_ERROR" to PlaybackException.ERROR_CODE_REMOTE_ERROR,
            "BEHIND_LIVE_WINDOW" to PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            "TIMEOUT" to PlaybackException.ERROR_CODE_TIMEOUT,
            "FAILED_RUNTIME_CHECK" to PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
            // DECODER_* は接頭辞が DECODING_* と異なる。order の字義どおり Unknown（仕様にない業務条件を足さない）。
            "DECODER_INIT_FAILED" to PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            "DECODER_QUERY_FAILED" to PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            "AUDIO_TRACK_INIT_FAILED" to PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            "AUDIO_TRACK_WRITE_FAILED" to PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            "AUDIO_TRACK_OFFLOAD_WRITE_FAILED" to PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
            "AUDIO_TRACK_OFFLOAD_INIT_FAILED" to PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
            "DRM_UNSPECIFIED" to PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            "DRM_SCHEME_UNSUPPORTED" to PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            "DRM_PROVISIONING_FAILED" to PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            "DRM_CONTENT_ERROR" to PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            "DRM_LICENSE_ACQUISITION_FAILED" to PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            "DRM_DISALLOWED_OPERATION" to PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
            "DRM_SYSTEM_ERROR" to PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            "DRM_DEVICE_REVOKED" to PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
            "DRM_LICENSE_EXPIRED" to PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
            "VIDEO_FRAME_PROCESSOR_INIT_FAILED" to PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
            "VIDEO_FRAME_PROCESSING_FAILED" to PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        )
        unknownConstants.forEach { (name, code) ->
            assertEquals(
                "ERROR_CODE_$name(=$code)",
                PlaybackFailureReason.Unknown(code),
                classifyPlaybackError(code),
            )
        }
    }

    @Test
    fun `T2a カスタム領域と未定義コードも Unknown にコードを保持する`() {
        val customBase = PlaybackException.CUSTOM_ERROR_CODE_BASE
        assertEquals(PlaybackFailureReason.Unknown(customBase), classifyPlaybackError(customBase))
        assertEquals(PlaybackFailureReason.Unknown(999999), classifyPlaybackError(999999))
    }

    @Test
    fun `T2a Unknown はコードが違えば等しくない`() {
        assertNotEquals(PlaybackFailureReason.Unknown(1), PlaybackFailureReason.Unknown(2))
    }

    @Test
    fun `T2a fake は初期状態が Idle`() {
        assertEquals(PlaybackState.Idle, FakePlayerController().state.value)
    }

    @Test
    fun `T2a fake に Failed を注入すると Paused や Idle と区別できる`() {
        val fake = FakePlayerController()

        fake.setState(PlaybackState.Failed(PlaybackFailureReason.Source))

        assertEquals(PlaybackState.Failed(PlaybackFailureReason.Source), fake.state.value)
        assertTrue(fake.state.value !is PlaybackState.Paused)
        assertNotEquals(PlaybackState.Idle, fake.state.value)
    }

    @Test
    fun `T2a fake は Paused から Failed へ同じ実体の上で遷移する`() {
        val fake = FakePlayerController()
        fake.setState(PlaybackState.Paused(10.0, 600.0))
        assertEquals(PlaybackState.Paused(10.0, 600.0), fake.state.value)

        fake.setState(PlaybackState.Failed(PlaybackFailureReason.Network))

        assertEquals(PlaybackState.Failed(PlaybackFailureReason.Network), fake.state.value)
        assertNotEquals(PlaybackState.Paused(10.0, 600.0), fake.state.value)
        assertNotEquals(PlaybackState.Idle, fake.state.value)
    }

    @Test
    fun `T2a fake の setState は既存の isPlaying を派生させない`() {
        val fake = FakePlayerController()

        fake.setState(PlaybackState.Playing(1.0, 600.0))

        assertEquals(false, fake.isPlaying.value)
    }
}
