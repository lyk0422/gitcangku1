package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 豁免审批请求：审批人确认其看到的申请版本。
 *
 * <p>同一审批人不能审批两次；仅当两名不同审批人都针对当前申请版本确认后豁免才生效。
 *
 * @param approver         审批人标识，不可为空
 * @param exceptionVersion 审批人看到的申请版本号，与库中当前版本不一致时返回 409
 */
public record ApproveRiskExceptionRequest(
        @NotBlank @Size(max = 64) String approver,
        @NotNull @Positive Integer exceptionVersion) {
}
