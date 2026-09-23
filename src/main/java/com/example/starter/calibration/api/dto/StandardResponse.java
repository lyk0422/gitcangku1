package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 标准器响应。
 *
 * @param id         标准器自增 ID
 * @param standardId 标准器业务 ID
 * @param name       标准器名称
 * @param createdAt  创建时间（UTC）
 */
public record StandardResponse(
        long id,
        String standardId,
        String name,
        Instant createdAt) {
}
