package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 案件保管人授权实体，对应 case_custodian_grant 表。
 *
 * @param id          主键
 * @param caseKey     案件业务键
 * @param custodianId 被授权保管人标识
 * @param active      授权是否有效：true 有效 / false 已撤销
 * @param createdAt   授权创建时间（UTC）
 */
public record CaseCustodianGrant(
        Long id,
        String caseKey,
        String custodianId,
        boolean active,
        LocalDateTime createdAt) {
}
