package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ResumeRecord;

/**
 * 人工恢复记录视图。
 */
public record ResumeRecordView(long resumeId, int newRound, String reason, String resumedAtUtc) {

    public static ResumeRecordView of(ResumeRecord record) {
        return new ResumeRecordView(record.id(), record.newRound(), record.reason(), record.resumedAtUtc());
    }
}
