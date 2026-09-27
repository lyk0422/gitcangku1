package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 跨案移交撤销请求。须由双方不同保管人确认；仅追加反向链，不删除原移交。
 *
 * @param commandKey       幂等命令键
 * @param sourceConfirmer  来源方确认人，须为原移交的来源案件保管人
 * @param targetConfirmer  目标方确认人，须为原移交的目标案件保管人，且与来源确认人不同
 */
public record CaseTransferRevokeRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String sourceConfirmer,
        @NotBlank @Size(max = 64) String targetConfirmer) {
}
