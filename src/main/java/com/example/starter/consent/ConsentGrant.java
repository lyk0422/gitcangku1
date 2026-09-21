package com.example.starter.consent;

import java.time.LocalDateTime;

/**
 * 授权代次实体，对应 consent_grant 表。
 *
 * @param id         主键
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途
 * @param epoch      授权代次，同一主体+用途内从 1 递增
 * @param status     状态：ACTIVE=有效，REVOKED=已撤回
 * @param requestId  产生本代授权的幂等请求标识
 * @param createdAt  授权时间（本地时间）
 * @param revokedAt  撤回时间（本地时间），未撤回为 null
 */
public record ConsentGrant(
        Long id,
        String subjectKey,
        Purpose purpose,
        int epoch,
        GrantStatus status,
        String requestId,
        LocalDateTime createdAt,
        LocalDateTime revokedAt) {
}
