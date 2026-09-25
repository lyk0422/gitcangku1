package com.example.starter.workblock.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * 创建施工占用窗口请求。requestKey 为幂等键；sectionIds 为区段集合，换序视为同参。
 * 区间 [startUtc, endUtc) 为 UTC 左闭右开。
 */
public record CreateWorkBlockRequest(
        @NotBlank String requestKey,
        @NotBlank String workKey,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @NotEmpty List<@NotBlank String> sectionIds,
        @NotBlank String operator) {
}
