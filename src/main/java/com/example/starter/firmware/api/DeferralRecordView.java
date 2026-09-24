package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.DeferralRecord;

/**
 * 任务顺延统计视图（只读）：同设备同发布单的累计顺延次数与最近顺延时刻。
 */
public record DeferralRecordView(long releaseId, String deviceId, int deferCount,
                                 String lastDeferredAtUtc) {

    public static DeferralRecordView of(DeferralRecord record) {
        return new DeferralRecordView(record.releaseId(), record.deviceId(), record.deferCount(),
                record.lastDeferredAtUtc());
    }
}
