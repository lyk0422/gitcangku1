package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 跨案移交发起请求。一次请求可包含多个证物，服务端按证物键规范化排序后在同一事务内处理。
 *
 * @param commandKey    幂等命令键（requestId）
 * @param fromCaseKey   来源案件键，不得与目标案件相同
 * @param toCaseKey     目标案件键
 * @param orderKey      移交令编号
 * @param orderVersion  移交令版本（按 UTC 左闭右开区间判定是否生效）
 * @param fromCustodian 来源案件保管人，须具备来源案件权限、与目标保管人不同，且为操作人本人
 * @param toCustodian   目标案件保管人，须具备目标案件权限
 * @param evidenceKeys  待移交证物业务键列表，非空、不可重复；实际数量须与要求值一致
 */
public record CaseTransferInitiateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String fromCaseKey,
        @NotBlank @Size(max = 64) String toCaseKey,
        @NotBlank @Size(max = 64) String orderKey,
        @NotBlank @Size(max = 32) String orderVersion,
        @NotBlank @Size(max = 64) String fromCustodian,
        @NotBlank @Size(max = 64) String toCustodian,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys) {
}
