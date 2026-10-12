package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.SessionRefreshResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * Single-flight contract for [SharedAuthRefresh]: concurrent auth failures across
 * metrics must trigger exactly one passport refresh whose outcome heals every caller.
 */
class SharedAuthRefreshTest {

    @Test
    fun concurrentCallers_shareOneRefreshAndAllSucceed() = runTest {
        var refreshCalls = 0
        val gate = SharedAuthRefresh {
            refreshCalls++
            SessionRefreshResult.Success
        }
        val results = (1..10).map {
            async { gate.refreshOnce() }
        }.awaitAll()
        assertEquals(1, refreshCalls)
        assertTrue(results.all { it is SessionRefreshResult.Success })
    }

    @Test
    fun failureOutcome_isSharedByEveryCaller() = runTest {
        var refreshCalls = 0
        val failure = SessionRefreshResult.NeedsReLogin("dead token")
        val gate = SharedAuthRefresh {
            refreshCalls++
            failure
        }
        val results = (1..5).map {
            async { gate.refreshOnce() }
        }.awaitAll()
        assertEquals(1, refreshCalls)
        assertTrue(results.all { it === failure })
    }

    @Test
    fun reset_allowsOneRefreshPerRun() = runTest {
        var refreshCalls = 0
        val gate = SharedAuthRefresh {
            refreshCalls++
            SessionRefreshResult.Success
        }
        gate.refreshOnce()
        gate.refreshOnce()
        assertEquals(1, refreshCalls)
        gate.reset()
        gate.refreshOnce()
        assertEquals(2, refreshCalls)
    }

    @Test
    fun cancelledLeader_unblocksFollowersWithTransientFailure() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gate = SharedAuthRefresh {
            entered.complete(Unit)
            release.await()
            SessionRefreshResult.Success
        }
        val leader = async {
            gate.refreshOnce()
        }
        entered.await()
        val follower = async {
            gate.refreshOnce()
        }
        leader.cancel()
        release.complete(Unit)
        val followerResult = withTimeout(5_000) { follower.await() }
        assertIs<SessionRefreshResult.TransientFailure>(followerResult)
    }

    @Test
    fun throwingRefresh_neverLeavesFollowersHanging() = runTest {
        val gate = SharedAuthRefresh {
            throw IllegalStateException("boom")
        }
        val results = withTimeout(5_000) {
            (1..3).map {
                async { gate.refreshOnce() }
            }.awaitAll()
        }
        assertTrue(results.all { it is SessionRefreshResult.TransientFailure })
    }
}
