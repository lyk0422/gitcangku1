package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseResumeRecord;

import java.time.Instant;
import java.util.List;

/**
 * 恢复记录视图及列表响应（只读）。
 */
public record ResumeRecordView(long id, long releaseId, int monitorRound, int version,
                               String reason, Instant resumedAt) {

    public static ResumeRecordView of(ReleaseResumeRecord record) {
        return new ResumeRecordView(record.id(), record.releaseId(), record.monitorRound(),
                record.version(), record.reason(), record.resumedAt());
    }

    /**
     * 恢复历史列表响应。
     */
    public record ResumeRecordListResponse(List<ResumeRecordView> records) {
    }
}
