package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 跨案移交请求。提交人（X-Actor-Id）必须等于 sourceCustodianId。
 * 证物列表在构造时规范化为字典序排序，保证同一批证物不同顺序产生相同幂等指纹。
 *
 * @param commandKey        幂等命令键（requestId）
 * @param sourceCaseKey     来源案件键
 * @param targetCaseKey     目标案件键，必须与来源案件不同
 * @param orderVersion      移交令版本，须处于有效期内
 * @param sourceCustodianId 来源案件保管人，须为全部证物当前保管人
 * @param targetCustodianId 目标案件保管人，须具备目标案件权限
 * @param evidenceKeys      证物业务键列表（服务端规范排序）
 * @param targetLocation    移交后存放位置；null 表示保持原位置不变
 */
public record CaseTransferRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String sourceCaseKey,
        @NotBlank @Size(max = 64) String targetCaseKey,
        @NotBlank @Size(max = 64) String orderVersion,
        @NotBlank @Size(max = 64) String sourceCustodianId,
        @NotBlank @Size(max = 64) String targetCustodianId,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys,
        @Size(max = 128) String targetLocation) {

    /**
     * 规范化：证物列表按字典序排序，保证指纹与请求顺序无关。
     */
    public CaseTransferRequest {
        if (evidenceKeys != null) {
            evidenceKeys = evidenceKeys.stream().sorted().toList();
        }
    }
}
