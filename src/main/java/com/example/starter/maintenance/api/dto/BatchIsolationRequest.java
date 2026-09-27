package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 批量能源隔离请求：先校验所有设备的完整后态，全部通过后在一个事务内整批生效；任一失败整批不生效。
 *
 * @param requestId   全局唯一请求标识（幂等键）
 * @param operatorId  批量创建操作人（固化到每条隔离令）
 * @param items       待隔离设备条目，至少一条
 */
public record BatchIsolationRequest(
        @NotBlank String requestId,
        @NotBlank String operatorId,
        @NotEmpty @Valid List<BatchIsolationItem> items) {
}
