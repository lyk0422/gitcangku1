package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.PauseRecord;

/**
 * 自动暂停记录视图。
 */
public record PauseRecordView(long pauseId, int monitorRound, long triggerTaskId,
                              int successCount, int failedCount, String pausedAtUtc) {

    public static PauseRecordView of(PauseRecord record) {
        return new PauseRecordView(record.id(), record.monitorRound(), record.triggerTaskId(),
                record.successCount(), record.failedCount(), record.pausedAtUtc());
    }
}
