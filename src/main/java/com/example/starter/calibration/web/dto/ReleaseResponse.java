package com.example.starter.calibration.web.dto;

import com.example.starter.calibration.service.ReleaseService;
import java.time.Instant;
import java.util.List;

/**
 * 批量放行成功响应。
 */
public record ReleaseResponse(
        String batchId,
        String releasedBy,
        Instant releasedAt,
        List<ReleaseRecordResponse> released) {

    public static ReleaseResponse from(ReleaseService.ReleaseResult result) {
        return new ReleaseResponse(
                result.batchId(),
                result.releasedBy(),
                result.releasedAt(),
                result.records().stream().map(ReleaseRecordResponse::from).toList());
    }
}
