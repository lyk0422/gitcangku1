package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 跨案移交撤销请求。仅限目标案件尚未发生后续交接；由双方不同保管人共同确认。
 *
 * @param commandKey    幂等命令键（requestId）
 * @param fromCustodian 确认撤销的来源案件保管人，须与原移交来源保管人一致
 * @param toCustodian   确认撤销的目标案件保管人，须与原移交目标保管人一致且与来源保管人不同
 */
public record CaseTransferRevokeRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String fromCustodian,
        @NotBlank @Size(max = 64) String toCustodian) {
}
