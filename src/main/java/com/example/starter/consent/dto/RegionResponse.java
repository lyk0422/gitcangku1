package com.example.starter.consent.dto;

import java.time.Instant;

/**
 * 区域登记响应。
 *
 * @param regionCode 区域代码
 * @param createdAt  登记时间（UTC）
 */
public record RegionResponse(String regionCode, Instant createdAt) {
}
