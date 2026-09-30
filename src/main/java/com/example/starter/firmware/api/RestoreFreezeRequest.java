package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 冻结恢复申请：由操作人（题目场景中即冻结创建人）提交原因，等待异于冻结人的审批人批准。
 */
public record RestoreFreezeRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 256) String reason) {
}
