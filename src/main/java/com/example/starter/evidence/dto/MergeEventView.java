package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 合并事件视图：附在主号轨迹上，记录合并时两证物的身份与版本。
 *
 * @param primaryKey     合并后保留的主号证物业务键
 * @param aliasKey       被合并转为别名的证物业务键
 * @param actorId        执行合并的操作人（合并时两证物的当前保管人）
 * @param primaryVersion 合并完成时主号版本
 * @param aliasVersion   合并完成时别名版本
 * @param createdAt      合并时间（Asia/Shanghai）
 */
public record MergeEventView(
        String primaryKey,
        String aliasKey,
        String actorId,
        long primaryVersion,
        long aliasVersion,
        LocalDateTime createdAt) {
}
