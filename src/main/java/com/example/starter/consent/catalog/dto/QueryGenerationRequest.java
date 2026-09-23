package com.example.starter.consent.catalog.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 查询代次签发请求：不传 catalogGeneration 时固定到当前最新目录代次。
 *
 * @param catalogGeneration 期望固定的目录代次；为空表示使用最新代次
 */
public record QueryGenerationRequest(
        @Min(1) @Max(1_000_000) Integer catalogGeneration) {
}
