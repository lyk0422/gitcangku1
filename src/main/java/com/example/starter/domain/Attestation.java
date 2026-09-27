package com.example.starter.domain;

import java.time.Instant;

/**
 * 单个发布角色对制品版本摘要的见证记录。
 *
 * <p>同一制品版本同一角色至多一条；撤销（state=REVOKED）为终态，记录保留。
 *
 * @param artifactId    所属制品版本 ID
 * @param role          见证发布角色（即提交见证的操作者）
 * @param checksumValue 见证提交的摘要
 * @param active        true=有效见证；false=已撤销
 * @param attestedAt    见证提交时间，UTC
 * @param revokedAt     撤销时间，UTC；未撤销为 null
 * @param revokedBy     撤销操作者；未撤销为 null
 */
public record Attestation(
        long id,
        long artifactId,
        String role,
        String checksumValue,
        boolean active,
        Instant attestedAt,
        Instant revokedAt,
        String revokedBy) {
}
