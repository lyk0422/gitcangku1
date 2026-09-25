package com.example.starter.work.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * 创建施工单请求。requestKey 为幂等键；窗口区间左闭右开，起止均为 UTC 时刻；
 * 区段集合换序视为同参（规范化后参与幂等指纹）。
 */
public record CreateWorkOrderRequest(
        @NotBlank String requestKey,
        @NotBlank String operator,
        @NotBlank String workKey,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @NotNull @Size(min = 1, max = 30) List<@NotBlank String> sectionIds) {
}
