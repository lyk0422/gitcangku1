package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 批量保障改签决策记录（追加式历史，不可改写）：固化决策所依据的前车版本、
 * 后车原新始发时刻与修复的保障差额。
 *
 * @param id                 主键
 * @param batchKey           批次标识，同一次批量保障改签共享
 * @param successorPlanId    被保障改签的后车计划 id
 * @param successorVersion   决策生效后后车计划新版本
 * @param predecessorVersion 决策所依据的约束前车版本（保障约束最紧的接续前车）
 * @param oldFirstStartUtc   后车改签前始发时刻，UTC
 * @param newFirstStartUtc   后车改签后始发时刻，UTC
 * @param shiftMinutes       整体后移分钟数
 * @param deficitMinutes     决策时修复的保障差额（各接续中最大缺口），分钟
 * @param createdAt          决策时刻，UTC 毫秒
 */
public record GuaranteeDecision(long id, String batchKey, long successorPlanId,
                                int successorVersion, int predecessorVersion,
                                Instant oldFirstStartUtc, Instant newFirstStartUtc,
                                long shiftMinutes, long deficitMinutes, long createdAt) {
}
