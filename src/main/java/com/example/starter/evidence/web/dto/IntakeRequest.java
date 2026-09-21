package com.example.starter.evidence.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 证物入库请求。commandKey 为幂等键；业务字段入库后不可修改。
 */
public record IntakeRequest(
        @NotBlank(message = "不能为空") @Size(max = 64) String commandKey,
        @NotBlank(message = "不能为空") @Size(max = 64) String evidenceKey,
        @NotBlank(message = "不能为空") @Size(max = 64) String caseKey,
        @NotBlank(message = "不能为空") @Size(max = 64) String category,
        @NotBlank(message = "不能为空") @Size(max = 64) String sealNo,
        @NotBlank(message = "不能为空") @Size(max = 64) String custodianId
) {
}
