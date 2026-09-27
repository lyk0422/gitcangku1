package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 跨案移交执行请求。证物键列表由服务端规范排序（升序）后在同一事务内处理；
 * 任一证物校验失败则整批不写入任何保管链。
 *
 * @param requestId         幂等请求标识（指纹含规范化证物、两案、令版本与操作者）
 * @param sourceCaseKey     来源案件键
 * @param targetCaseKey     目标案件键，须与来源案件不同
 * @param orderVersion      移交令版本，须已登记且有效
 * @param sourceCustodianId 来源案件保管人，须具备来源案件权限且为全部证物当前保管人
 * @param targetCustodianId 目标案件保管人，须具备目标案件权限，且与来源保管人不同
 * @param evidenceKeys      待移交证物业务键列表，非空、不含重复
 */
public record CaseTransferExecuteRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String sourceCaseKey,
        @NotBlank @Size(max = 64) String targetCaseKey,
        @NotBlank @Size(max = 64) String orderVersion,
        @NotBlank @Size(max = 64) String sourceCustodianId,
        @NotBlank @Size(max = 64) String targetCustodianId,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys) {
}
