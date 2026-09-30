package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 冻结恢复审批请求：审批人必须与冻结创建人不同，且存在恢复申请；
 * 未申请恢复或同人审批返回 422，且不改变冻结状态与审计记录。
 */
public record ApproveFreezeResumeRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String approver) {
}
