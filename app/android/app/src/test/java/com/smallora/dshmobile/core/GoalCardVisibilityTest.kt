package com.smallora.dshmobile.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 目标卡片展示规则：完成的自动隐藏、手动关闭按 goal.id 记住、新目标重新展示。 */
class GoalCardVisibilityTest {

    private fun goal(id: String = "g1", phase: String = "active") =
        Goal(id = id, objective = "目标", phase = phase, blockedReason = null, revision = 1)

    @Test
    fun finishedPhasesAreHidden() {
        assertTrue(isGoalFinished("complete"))
        assertTrue(isGoalFinished("completed"))
        assertFalse(isGoalFinished("active"))
        assertFalse(isGoalFinished("paused"))
        assertFalse(isGoalFinished("blocked"))
        assertFalse(isGoalFinished(null))

        assertFalse(shouldShowGoalCard(goal(phase = "complete"), null))
    }

    @Test
    fun unfinishedGoalIsShownByDefault() {
        assertTrue(shouldShowGoalCard(goal(), null))
        assertTrue(shouldShowGoalCard(goal(phase = "paused"), null))
        assertTrue(shouldShowGoalCard(goal(phase = "blocked"), null))
    }

    @Test
    fun dismissedGoalIdHidesOnlyThatGoal() {
        assertFalse(shouldShowGoalCard(goal(id = "g1"), dismissedGoalId = "g1"))
        // 新目标（不同 id）应重新展示
        assertTrue(shouldShowGoalCard(goal(id = "g2"), dismissedGoalId = "g1"))
    }

    @Test
    fun noGoalMeansNoCard() {
        assertFalse(shouldShowGoalCard(null, null))
        assertFalse(shouldShowGoalCard(null, "g1"))
    }

    @Test
    fun chatUiStateDefaultsToNoDismissedGoal() {
        val state = ChatReducer.initial("s1")
        assertTrue(state.dismissedGoalId == null)
    }
}
