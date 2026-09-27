package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 跨案移交请求。发起操作人（X-Actor-Id）必须等于来源案件保管人；
 * 证物列表由服务端去重校验后按字典序规范化排序，整批在单事务内完成。
 *
 * @param commandKey         幂等命令键（requestId）
 * @param sourceCaseKey      来源案件键
 * @param targetCaseKey      目标案件键，须与来源案件不同
 * @param orderVersion       移交令版本
 * @param sourceCustodianId  来源案件保管人，须等于操作人
 * @param targetCustodianId  目标案件保管人，须与来源保管人不同
 * @param evidenceKeys       本批证物业务键，非空且不重复
 */
public record CaseTransferRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String sourceCaseKey,
        @NotBlank @Size(max = 64) String targetCaseKey,
        @NotBlank @Size(max = 64) String orderVersion,
        @NotBlank @Size(max = 64) String sourceCustodianId,
        @NotBlank @Size(max = 64) String targetCustodianId,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys) {
}
