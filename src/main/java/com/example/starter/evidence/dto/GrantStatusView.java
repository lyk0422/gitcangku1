package com.example.starter.evidence.dto;

/**
 * 案件保管人授权诊断项。
 *
 * @param caseKey     案件键
 * @param custodianId 保管人标识
 * @param active      是否持有有效授权
 */
public record GrantStatusView(
        String caseKey,
        String custodianId,
        boolean active) {
}
