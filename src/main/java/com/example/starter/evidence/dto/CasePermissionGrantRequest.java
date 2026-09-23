package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 案件权限登记请求。组合包归还的接收人/复核人均须事先登记对应案件权限。
 *
 * @param requestId 幂等请求键
 * @param caseKey   案件键
 * @param userId    被授权操作人标识
 */
public record CasePermissionGrantRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String caseKey,
        @NotBlank @Size(max = 64) String userId) {
}
