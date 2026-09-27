package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 证物跨案移交请求。一次请求可包含多件证物，服务端按 evidenceKey 规范化排序后整批处理；
 * 必须提交移交令编号与版本、双方案件键及双方保管人。
 *
 * @param commandKey    幂等命令键（requestId）
 * @param orderNo       移交令编号
 * @param orderVersion  移交令版本（正整数）
 * @param fromCaseKey   来源案件键，不得与目标案件相同
 * @param toCaseKey     目标案件键
 * @param fromCustodian 来源案件保管人（操作者本人），须与目标保管人不同且持有来源案授权
 * @param toCustodian   目标案件保管人，须持有目标案授权
 * @param evidenceKeys  待移交证物业务键列表，非空、不可重复；服务端规范化排序
 */
public record CaseTransferRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String orderNo,
        @NotNull @Positive Integer orderVersion,
        @NotBlank @Size(max = 64) String fromCaseKey,
        @NotBlank @Size(max = 64) String toCaseKey,
        @NotBlank @Size(max = 64) String fromCustodian,
        @NotBlank @Size(max = 64) String toCustodian,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys) {
}
