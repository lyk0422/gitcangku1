package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 跨案移交撤销请求。仅目标案件尚未发生后续借出、封签复核与保管链交接时允许撤销；
 * 须由双方不同保管人确认（操作人须为其中一方），撤销只追加反向链，不删除原移交。
 *
 * @param commandKey    幂等命令键（requestId）
 * @param fromCustodian 确认撤销的来源案件保管人（须与移交单记录一致）
 * @param toCustodian   确认撤销的目标案件保管人（须与移交单记录一致）
 */
public record CaseTransferRevokeRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String fromCustodian,
        @NotBlank @Size(max = 64) String toCustodian) {
}
