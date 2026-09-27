package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 批量隔离请求：一个 requestId 覆盖整批，先校验全部设备的完整后态，
 * 任一设备校验失败整批不生效；成功时所有隔离令在同一事务内提交。
 *
 * @param requestId  全局唯一请求标识（幂等键，整批共用）
 * @param operatorId 创建操作人（整批共用）
 * @param items      各设备隔离项；设备重复、锁定点重复等均导致整批 422
 */
public record BatchIsolationRequest(
        @NotBlank String requestId,
        @NotBlank String operatorId,
        @NotEmpty @Size(max = 100) @Valid List<@Valid BatchIsolationItemRequest> items) {
}
