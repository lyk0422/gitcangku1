package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建完工后更正附录请求。只能对已完成周期创建，不能重开周期或覆盖工时历史。
 *
 * @param requestId   全局唯一请求标识（幂等键）
 * @param operatorId  创建附录的操作人员
 * @param note        更正说明（非空）
 */
public record CreateAppendixRequest(
        @NotBlank String requestId,
        @NotBlank String operatorId,
        @NotBlank @Size(max = 500) String note) {
}
