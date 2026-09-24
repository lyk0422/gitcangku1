package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 拒绝销毁令请求。审批人取自 X-Actor-Id；拒绝原因写入后不可改写，
 * 任一审批人拒绝立即使销毁令进入 REJECTED 终态。
 *
 * @param commandKey 幂等命令键
 * @param reason    非空拒绝原因
 */
public record DestructionRejectRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 512) String reason) {
}
