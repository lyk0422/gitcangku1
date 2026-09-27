package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 跨案移交撤销请求。须由双方不同保管人共同确认；
 * 操作人（X-Actor-Id）必须是其中一方确认人。
 *
 * @param commandKey          幂等命令键
 * @param sourceConfirmerId   来源案件方确认保管人
 * @param targetConfirmerId   目标案件方确认保管人，须与来源方不同
 */
public record CaseTransferRevokeRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String sourceConfirmerId,
        @NotBlank @Size(max = 64) String targetConfirmerId) {
}
