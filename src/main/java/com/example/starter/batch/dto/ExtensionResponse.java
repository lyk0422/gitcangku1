package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 一条延期历史记录（不可变）。记录提交与确认双方、本次顺延分钟与确认生效时刻；
 * 祖先召回后已生效记录仍保留，但不恢复批次可用。
 */
public record ExtensionResponse(
        String extensionKey,
        String batchKey,
        String recheckConclusion,
        int extendMinutes,
        String reviewerId,
        String confirmerId,
        int sequence,
        String status,
        Instant submittedAt,
        Instant confirmedAt
) {
}
