package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 批量创建能源隔离令请求：先校验所有设备的完整锁定点集合与最终状态，任一失败整批不生效（单事务）。
 *
 * @param requestId  全局唯一请求标识（幂等键）
 * @param operator   批量创建操作者（逐令固化）
 * @param orders     待创建隔离令集合（至少一项）；设备、隔离令标识不可重复
 */
public record BatchCreateIsolationRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotEmpty @Valid List<@Valid BatchIsolationItem> orders) {
}
