package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 标准器版本响应。
 *
 * @param id              版本自增 ID
 * @param versionKey      版本业务键
 * @param standardId      所属标准器业务 ID
 * @param parentVersionId 上级标准器版本 ID；null 表示根版本
 * @param validFrom       有效期起点（UTC，含）
 * @param validTo         有效期终点（UTC，不含）
 * @param certificateNo   校准证书号
 * @param status          VALID / INVALID
 * @param createdAt       创建时间（UTC）
 */
public record StandardVersionResponse(
        long id,
        String versionKey,
        String standardId,
        Long parentVersionId,
        Instant validFrom,
        Instant validTo,
        String certificateNo,
        String status,
        Instant createdAt) {
}
