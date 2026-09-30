package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 离群复核响应。
 *
 * @param id                  复核记录 ID
 * @param reviewer            复核人
 * @param decision            复核结论 ACCEPT / REJECT
 * @param reason              复核理由
 * @param measurementRevision 引用的测量修订版本号
 * @param certificateId       引用的证书版本 ID
 * @param stale               是否被标记 STALE（不计入裁决）
 * @param createdAt           复核时间（UTC）
 */
public record ReviewResponse(
        long id,
        String reviewer,
        String decision,
        String reason,
        int measurementRevision,
        long certificateId,
        boolean stale,
        Instant createdAt) {
}
