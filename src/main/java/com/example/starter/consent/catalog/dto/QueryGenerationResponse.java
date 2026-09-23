package com.example.starter.consent.catalog.dto;

import java.time.Instant;

/**
 * 查询代次签发响应。
 *
 * @param token             不透明查询令牌，批量查询必须携带
 * @param catalogGeneration 令牌固定的目录代次
 * @param issuedAt          签发时间（UTC）
 */
public record QueryGenerationResponse(String token, int catalogGeneration, Instant issuedAt) {
}
