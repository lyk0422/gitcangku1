package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 案件保管人授权请求。新案件首名保管人可自助登记；
 * 之后仅该案件已有保管人可追加授权。
 *
 * @param commandKey  幂等命令键
 * @param caseKey     案件键
 * @param custodianId 被授权保管人
 */
public record CaseCustodianGrantRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String caseKey,
        @NotBlank @Size(max = 64) String custodianId) {
}
