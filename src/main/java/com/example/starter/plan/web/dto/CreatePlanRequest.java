package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * 创建草稿计划请求。requestKey 为幂等键；占用清单 1～30 条。
 * consist 为空表示纯区段占用、不做站台适配；非空时每条占用必须携带申请站台。
 */
public record CreatePlanRequest(
        @NotBlank String requestKey,
        @NotBlank String scheduleKey,
        @NotNull LocalDate opDate,
        @Valid ConsistSpec consist,
        @NotNull @Size(min = 1, max = 30) List<@Valid OccupancyRequest> occupancies) {
}
