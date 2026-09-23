package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.RollbackPauseRecord;

/**
 * 回退计划自动暂停记录视图。
 */
public record RollbackPauseRecordView(long id, long planId, int monitorRound, int hopIndex,
                                      long triggerHopTaskId, int successCount, int failedCount,
                                      String pausedAtUtc) {

    public static RollbackPauseRecordView of(RollbackPauseRecord record) {
        return new RollbackPauseRecordView(record.id(), record.planId(), record.monitorRound(),
                record.hopIndex(), record.triggerHopTaskId(), record.successCount(),
                record.failedCount(), record.pausedAtUtc());
    }
}
