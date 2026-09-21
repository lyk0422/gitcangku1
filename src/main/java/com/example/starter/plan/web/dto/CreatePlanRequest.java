package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * 创建草稿计划请求。requestKey 为幂等键；占用清单 1～30 条。
 */
public record CreatePlanRequest(
        @NotBlank String requestKey,
        @NotBlank String scheduleKey,
        @NotNull LocalDate opDate,
        @NotNull @Size(min = 1, max = 30) List<@Valid OccupancyRequest> occupancies) {
}
