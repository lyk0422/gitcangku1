package com.example.starter.evidence.destruction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 审批拒绝请求。任一审批人拒绝立即使销毁令进入 REJECTED 终态，拒绝原因写入一次、不可改写。
 *
 * @param commandKey 幂等命令键
 * @param reason     拒绝原因，非空，不可改写
 */
public record DestructionRejectRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 512) String reason) {
}
