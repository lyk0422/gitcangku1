package com.example.starter.cue.domain;

import java.time.LocalDateTime;

/**
 * 排练场次 PO。
 *
 * @param id              场次 ID
 * @param playCode        剧目编码
 * @param sessionCode     场次业务编码
 * @param versionId       当前绑定版本 ID，未绑定为空；运行后不再变化
 * @param status          场次状态
 * @param totalCues       冻结快照提示点总数，未冻结为 0
 * @param executedSeq     已连续成功执行到的序号，从 0 开始，失败不推进
 * @param frozenVersionId 冻结版本 ID，未冻结为空
 * @param frozenAt        冻结时间（Asia/Shanghai），未冻结为空
 * @param completedAt     结束时间（Asia/Shanghai），未结束为空
 * @param createdAt       创建时间（Asia/Shanghai）
 */
public record RehearsalSession(
        Long id,
        String playCode,
        String sessionCode,
        Long versionId,
        SessionStatus status,
        int totalCues,
        int executedSeq,
        Long frozenVersionId,
        LocalDateTime frozenAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt) {
}
