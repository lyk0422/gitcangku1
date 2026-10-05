package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 批量放行请求。每批 1～50 条测量键，整批原子生效。
 *
 * @param keys            测量键列表
 * @param requestId       幂等请求 ID；可选，缺省时不做去重
 * @param expectedVersion 期望版本；可选，仅作为幂等身份的一部分参与结构化比较
 */
public record ReleaseRequest(List<String> keys, String requestId, String expectedVersion) {
}
