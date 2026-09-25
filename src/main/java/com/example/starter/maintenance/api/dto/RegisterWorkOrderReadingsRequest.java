package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 工单进行期间批量登记读数请求：先按最终读数序列、窗口、基线与版本整体预校验，
 * 任一越窗、倒退或版本失配返回 422，全部读数与工单状态回滚。
 *
 * @param workOrderKey      工单幂等键，指纹含读数摘要
 * @param expectedVersion   设备期望版本号
 * @param workOrderVersion  工单期望版本号
 * @param readings          本批次读数（至少一条）
 */
public record RegisterWorkOrderReadingsRequest(
        @NotBlank String workOrderKey,
        @NotNull Long expectedVersion,
        @NotNull Long workOrderVersion,
        @NotEmpty @Valid List<WorkOrderReadingItem> readings) {
}
