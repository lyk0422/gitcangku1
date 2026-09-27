package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 跨案移交请求。一次请求可包含多个证物，服务端按证物键规范排序后在同一事务内处理。
 *
 * @param commandKey      幂等命令键（requestId）；指纹含规范化证物列表、两案、令版本与操作者
 * @param sourceCaseKey   来源案件键，必须与目标案件不同
 * @param targetCaseKey   目标案件键
 * @param orderVersion    移交令版本，须在有效期内（UTC 左闭右开）
 * @param sourceCustodian 来源案件保管人，须为全部证物当前保管人
 * @param targetCustodian 目标案件保管人，须与来源保管人不同
 * @param evidenceKeys    证物业务键列表，非空、不重复；服务端按字典序规范排序处理
 */
public record CaseTransferRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String sourceCaseKey,
        @NotBlank @Size(max = 64) String targetCaseKey,
        @NotBlank @Size(max = 64) String orderVersion,
        @NotBlank @Size(max = 64) String sourceCustodian,
        @NotBlank @Size(max = 64) String targetCustodian,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys) {
}
