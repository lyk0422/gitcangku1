package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 同行复核记录响应。复核记录不可变，固化被复核版本、证书版本、结论、说明与时刻。
 *
 * @param id             复核记录 ID
 * @param reviewKey      复核业务键
 * @param measurementKey 被复核测量的业务键
 * @param version        被复核的测量版本号
 * @param certificateId  复核时关联的证书 ID（证书版本固化）
 * @param conclusion     结论：PASS / RETURN
 * @param state          状态：VALID 有效 / STALE 失效
 * @param reviewer       复核人
 * @param comment        复核说明
 * @param createdAt      复核提交时间（UTC）
 */
public record ReviewResponse(
        long id,
        String reviewKey,
        String measurementKey,
        int version,
        long certificateId,
        String conclusion,
        String state,
        String reviewer,
        String comment,
        Instant createdAt) {
}
