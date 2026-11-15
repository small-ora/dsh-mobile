package com.smallora.dshmobile.core

/**
 * 目标（Goal）卡片的展示规则。
 *
 * 背景：聊天页底部常驻目标卡片（目标 + 待办 + 暂停/完成）。目标完成后卡片仍会一直占位，
 * 把后面的消息/输入区挤上去；因此：
 * - `phase == "complete"`（兼容 "completed"）时**自动隐藏**；
 * - 任意阶段都可手动关闭（按 goal.id 记住，用户换了新目标会重新展示）。
 */

/** DSH 目标阶段是否已结束（完成）。 */
fun isGoalFinished(phase: String?): Boolean = phase == "complete" || phase == "completed"

/** 目标卡片是否应展示：存在目标、未完成、且未被用户手动关闭。 */
fun shouldShowGoalCard(goal: Goal?, dismissedGoalId: String?): Boolean {
    if (goal == null) return false
    if (isGoalFinished(goal.phase)) return false
    return goal.id != dismissedGoalId
}
