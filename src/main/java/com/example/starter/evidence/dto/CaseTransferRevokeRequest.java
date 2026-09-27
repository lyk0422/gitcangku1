package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 跨案移交撤销请求。仅目标案件尚未发生后续交接时允许；
 * 须由双方不同保管人确认，追加反向链而不删除原移交。
 *
 * @param requestId       幂等请求标识
 * @param sourceConfirmBy 来源案件确认保管人，须具备来源案件权限
 * @param targetConfirmBy 目标案件确认保管人，须具备目标案件权限，且与来源确认人不同
 */
public record CaseTransferRevokeRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String sourceConfirmBy,
        @NotBlank @Size(max = 64) String targetConfirmBy) {
}
