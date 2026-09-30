package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 恢复审批请求：审批人必须不同于冻结创建人；同人审批返回 422 且不改变冻结状态。
 */
public record ApproveRestoreRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String approver) {
}
