package com.rioikeda.newslisten.onboarding.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.settings.app.FakeSourcesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OnboardingCommands] の挙動（android-dec-116）。状態はこの class の中で閉じる。 */
class OnboardingCommandsTest {

    private fun TestScope.newCommands(api: FakeSourcesApi = FakeSourcesApi()): OnboardingCommands =
        OnboardingCommands(api, StandardTestDispatcher(testScheduler))

    @Test
    fun 取得前のonboardingCompletedはnull() = runTest {
        assertNull(newCommands().onboardingCompleted.value)
    }

    @Test
    fun refreshOnboardingStatus成功でonboardingCompletedにサーバー値が反映される() = runTest {
        val api = FakeSourcesApi()
        val falseCommands = newCommands(api)
        api.onFetchOnboardingStatus = { false }
        falseCommands.refreshOnboardingStatus()
        assertEquals(false, falseCommands.onboardingCompleted.value)

        val trueCommands = newCommands(api)
        api.onFetchOnboardingStatus = { true }
        trueCommands.refreshOnboardingStatus()
        assertEquals(true, trueCommands.onboardingCompleted.value)
        assertTrue("refresh が書込を呼んだ: ${api.writeCalls}", api.writeCalls.isEmpty())
    }

    @Test
    fun refreshOnboardingStatus失敗時は行き止まり防止のためtrue扱いになる() = runTest {
        val api = FakeSourcesApi().apply { onFetchOnboardingStatus = { throw ApiException.HttpError(500) } }
        val commands = newCommands(api)

        commands.refreshOnboardingStatus()

        assertEquals(true, commands.onboardingCompleted.value)
    }

    @Test
    fun finish成功でcompleteOnboardingが呼ばれonboardingCompletedがtrueになる() = runTest {
        val api = FakeSourcesApi()
        val commands = newCommands(api)

        commands.finishOnboarding()

        assertEquals(1, api.completeOnboardingCallCount)
        assertEquals(true, commands.onboardingCompleted.value)
    }

    @Test
    fun finishはcompleteOnboarding失敗でもベストエフォートでonboardingCompletedをtrueにする() = runTest {
        val api = FakeSourcesApi().apply { onCompleteOnboarding = { throw ApiException.HttpError(500) } }
        val commands = newCommands(api)

        commands.finishOnboarding()

        assertEquals(true, commands.onboardingCompleted.value)
    }

    @Test
    fun onboardingCompletedは前の値から次の操作の結果へ書き直される() = runTest {
        val api = FakeSourcesApi()
        val commands = newCommands(api)

        api.onFetchOnboardingStatus = { true }
        commands.refreshOnboardingStatus()
        assertEquals(true, commands.onboardingCompleted.value)

        api.onFetchOnboardingStatus = { false }
        commands.refreshOnboardingStatus()
        assertEquals(false, commands.onboardingCompleted.value)

        api.onFetchOnboardingStatus = { throw ApiException.HttpError(500) }
        commands.refreshOnboardingStatus()
        assertEquals(true, commands.onboardingCompleted.value)

        api.onFetchOnboardingStatus = { false }
        commands.refreshOnboardingStatus()
        assertEquals(false, commands.onboardingCompleted.value)

        commands.finishOnboarding()
        assertEquals(true, commands.onboardingCompleted.value)
    }

    @Test
    fun 入口の戻り値はUnitで固定される() = runTest {
        val commands = newCommands()

        val unit: Unit = commands.finishOnboarding()

        assertEquals(Unit, unit)
    }
}
