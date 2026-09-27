package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件指挥日志封存包实体，对应 incident_log_seals 表。
 * 版本在事件内从 1 递增，封存包一经提交不可变；事实错误只能追加更正附录。
 * cutoffAt 为截至 UTC 时刻（区间左闭右开，仅固化 occurredAt 严格小于 cutoffAt 的记录）；
 * contentHash 覆盖排序后全部明细条目（含任务依赖边），用于验真。
 */
public record LogSeal(
        long id,
        long incidentId,
        int version,
        Instant cutoffAt,
        int recordCount,
        int edgeCount,
        String contentHash,
        String sealedBy,
        Instant sealedAt) {
}
