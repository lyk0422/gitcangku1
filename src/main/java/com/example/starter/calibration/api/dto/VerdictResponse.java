package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 离群裁决响应。
 *
 * @param decision 裁决结论：ACCEPT / REJECT
 * @param formedAt 裁决形成时间（UTC）
 */
public record VerdictResponse(
        String decision,
        Instant formedAt) {
}
