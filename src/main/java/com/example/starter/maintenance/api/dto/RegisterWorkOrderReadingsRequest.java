package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 工单批量登记读数请求。先按设备最终读数序列、窗口与认证状态整体预校验，
 * 任一越窗、倒退或版本失配均返回 422，全部读数与工单状态一并回滚。
 *
 * @param expectedVersion          设备期望版本号，批量预校验失配时返回 422
 * @param expectedWorkOrderVersion 工单期望版本号，批量预校验失配时返回 422
 * @param readings                 本批读数（至少一条），全部校验通过才落库
 */
public record RegisterWorkOrderReadingsRequest(
        @NotNull Long expectedVersion,
        @NotNull Long expectedWorkOrderVersion,
        @NotNull @NotEmpty List<@Valid WorkOrderReadingItem> readings) {
}
