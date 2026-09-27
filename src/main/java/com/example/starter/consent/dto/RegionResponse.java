package com.example.starter.consent.dto;

import java.time.Instant;

/**
 * 区域响应：返回区域代码与登记时间。
 *
 * @param regionCode 驻留区域代码
 * @param createdAt  登记时间（UTC）
 */
public record RegionResponse(String regionCode, Instant createdAt) {
}
