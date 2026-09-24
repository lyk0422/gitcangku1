package com.example.starter.evidence.destruction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 审批同意请求。每名审批人对同一销毁令至多同意一次，两名审批人互不相同且都不同于提交人。
 *
 * @param commandKey 幂等命令键
 * @param reason     同意备注，可空
 */
public record DestructionAgreeRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @Size(max = 512) String reason) {
}
