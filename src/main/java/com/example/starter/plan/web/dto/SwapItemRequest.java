package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 容量交换参与项：为单个已发布计划提交期望版本、当前占用段与目标占用段。
 * 当前/目标占用段各 1～30 条；占用段顺序不影响语义，服务端按完整四元组规范化排序。
 */
public record SwapItemRequest(
        @NotBlank String scheduleKey,
        @NotNull Integer expectedVersion,
        @NotNull @Size(min = 1, max = 30) List<@Valid OccupancyRequest> currentOccupancies,
        @NotNull @Size(min = 1, max = 30) List<@Valid OccupancyRequest> targetOccupancies) {
}
