package com.example.starter.cue.dto;

import java.time.LocalDateTime;

/**
 * 排练场次视图。
 *
 * @param id              场次 ID
 * @param playCode        剧目编码
 * @param sessionCode     场次业务编码
 * @param status          场次状态：NOT_STARTED / RUNNING / FINISHED
 * @param boundVersionId  当前绑定的编排版本 ID；未绑定为空
 * @param frozenVersionId 冻结版本 ID；未开始为空，开始后不再变化
 * @param totalCues       冻结快照提示点总数，未冻结为 0
 * @param executedSeq     已连续成功执行到的序号，从 0 开始
 * @param frozenAt        冻结时间（Asia/Shanghai），未冻结为空
 * @param completedAt     结束时间（Asia/Shanghai），未结束为空
 * @param createdAt       场次创建时间（Asia/Shanghai）
 */
public record SessionResponse(
        Long id,
        String playCode,
        String sessionCode,
        String status,
        Long boundVersionId,
        Long frozenVersionId,
        int totalCues,
        int executedSeq,
        LocalDateTime frozenAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt) {
}
