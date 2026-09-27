package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 案件保管人授权实体，对应 case_custodian 表。
 *
 * @param id          主键
 * @param caseKey     案件键
 * @param custodianId 具备该案件保管权限的保管人标识
 * @param createdAt   授权登记时间（Asia/Shanghai）
 */
public record CaseCustodian(
        Long id,
        String caseKey,
        String custodianId,
        LocalDateTime createdAt) {
}
