package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 豁免审批请求：审批人通过该身份确认申请，同一申请须两名不同审批人确认同一内容版本后方可生效。
 *
 * @param approver       审批人标识（如账号），同一申请内不可重复
 * @param contentVersion 可选，调用方确认的申请内容版本；与申请当前版本不一致时 422
 */
public record ExceptionApproveRequest(@NotBlank @Size(max = 128) String approver,
                                      Integer contentVersion) {

    public ExceptionApproveRequest(String approver) {
        this(approver, null);
    }
}
