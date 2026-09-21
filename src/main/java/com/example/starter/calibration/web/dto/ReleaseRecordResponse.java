package com.example.starter.calibration.web.dto;

import com.example.starter.calibration.domain.ReleaseRecord;
import java.time.Instant;

/**
 * 放行记录视图。
 */
public record ReleaseRecordResponse(
        long id,
        long measurementId,
        long certificateId,
        String releasedBy,
        Instant releasedAt,
        String batchId) {

    public static ReleaseRecordResponse from(ReleaseRecord record) {
        return new ReleaseRecordResponse(
                record.id(),
                record.measurementId(),
                record.certificateId(),
                record.releasedBy(),
                record.releasedAt(),
                record.batchId());
    }
}
