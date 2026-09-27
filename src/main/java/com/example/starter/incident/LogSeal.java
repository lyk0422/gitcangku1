package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件指挥日志封存包实体，对应 incident_log_seals 表。
 * 版本号事件内从 1 递增；封存包只追加不更新，后续任务修订、撤销或事件合并
 * 都不得修改已提交包的内容，事实错误只能通过更正附录（LogCorrection）补充。
 * cutoffAt 为封存截至 UTC 时刻，区间左闭右开，仅固化 occurredAt 早于该时刻的记录；
 * sealedAt 为封存执行时刻（注入时钟）；contentHash 为包内容 SHA-256，用于校验不可变性。
 */
public record LogSeal(
        long id,
        long incidentId,
        int version,
        Instant cutoffAt,
        Instant sealedAt,
        String createdBy,
        int recordCount,
        String contentHash) {
}
