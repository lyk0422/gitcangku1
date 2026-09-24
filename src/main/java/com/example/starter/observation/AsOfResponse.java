package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 按时刻一致视图响应：返回目标 UTC 时刻及各 observationId（升序、去重）的逐条视图。
 * 只读接口：不推进观测版本、不写入冲突解决记录。
 *
 * @param asOfUtc 目标 UTC 时刻
 * @param entries 按 observationId 升序的逐条结果（PRESENT / DELETED / ABSENT）
 */
public record AsOfResponse(
        Instant asOfUtc,
        List<Entry> entries) {

    /**
     * 单条结果序列化时省略 null 字段：ABSENT 不带 version 与业务字段，DELETED 不带业务字段。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(
            String observationId,
            String state,
            Integer version,
            String location,
            String reading,
            String note,
            String lastResolutionId) {

        public static Entry of(AsOfEntry entry) {
            return new Entry(entry.observationId(), entry.state().name(), entry.version(),
                    entry.location(), entry.reading(), entry.note(), entry.lastResolutionId());
        }
    }
}
