package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 跨案移交撤销请求。由双方不同保管人共同确认；提交人（X-Actor-Id）必须是二者之一。
 * 撤销仅追加反向链，不删除原移交。
 *
 * @param commandKey              幂等命令键（requestId）
 * @param orderVersion            撤销使用的移交令版本，须处于有效期内
 * @param sourceCustodianId       来源方确认保管人，须具备来源案件权限
 * @param targetCustodianId       目标方确认保管人，须具备目标案件权限且为全部证物当前保管人
 */
public record CaseTransferRevokeRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String orderVersion,
        @NotBlank @Size(max = 64) String sourceCustodianId,
        @NotBlank @Size(max = 64) String targetCustodianId) {
}
