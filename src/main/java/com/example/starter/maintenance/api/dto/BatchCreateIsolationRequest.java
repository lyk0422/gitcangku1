package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 批量隔离多设备请求：先校验全部设备的完整锁定点集合，任一失败整批不生效。
 *
 * @param requestId  全局唯一请求标识（幂等键）
 * @param createdBy  创建操作者标识（对批内所有隔离令生效并固化）
 * @param orders     各设备的隔离令项（至少一项），请求内设备/隔离令重复返回 422
 */
public record BatchCreateIsolationRequest(
        @NotBlank String requestId,
        @NotBlank String createdBy,
        @NotEmpty @Valid List<BatchIsolationItem> orders) {
}
