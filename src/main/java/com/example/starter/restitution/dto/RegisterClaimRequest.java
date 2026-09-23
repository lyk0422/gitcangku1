package com.example.starter.restitution.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 登记主张请求。
 *
 * @param claimKey  案内唯一主张键
 * @param applicant 申请人标识（与 X-Actor-Id 可不同；自批以申请人为准）
 * @param statement 非空主张说明
 * @param items     主张覆盖的藏品子集（须为案件藏品清单的子集）
 */
public record RegisterClaimRequest(
        @NotBlank(message = "claimKey 不能为空") String claimKey,
        @NotBlank(message = "applicant 不能为空") String applicant,
        @NotBlank(message = "statement 不能为空") String statement,
        @NotEmpty(message = "items 不能为空")
        @Size(min = 1, max = 10, message = "藏品数量必须在 1 至 10 之间")
        List<@NotBlank(message = "藏品编号不能为空") String> items) {
}
