package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 版本化批量放行成功响应。
 *
 * @param batchId    放行批次 ID
 * @param releasedBy 放行人（X-Actor-Id）
 * @param releasedAt 放行时间（UTC）
 * @param released   已放行的测量键与修订号
 */
public record ReleaseVersionsResponse(String batchId, String releasedBy, Instant releasedAt,
                                      List<Item> released) {

    /**
     * 已放行项。
     *
     * @param measurementKey 业务测量键
     * @param revision       已放行的修订号
     */
    public record Item(String measurementKey, int revision) {
    }
}
