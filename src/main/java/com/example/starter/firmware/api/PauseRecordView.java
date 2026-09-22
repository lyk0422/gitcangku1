package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleasePauseRecord;

import java.time.Instant;
import java.util.List;

/**
 * 暂停记录视图及列表响应（只读）。
 */
public record PauseRecordView(long id, long releaseId, int monitorRound, long triggerTaskId,
                              int successCount, int failureCount, Instant pausedAt) {

    public static PauseRecordView of(ReleasePauseRecord record) {
        return new PauseRecordView(record.id(), record.releaseId(), record.monitorRound(),
                record.triggerTaskId(), record.successCount(), record.failureCount(), record.pausedAt());
    }

    /**
     * 暂停历史列表响应。
     */
    public record PauseRecordListResponse(List<PauseRecordView> records) {
    }
}
