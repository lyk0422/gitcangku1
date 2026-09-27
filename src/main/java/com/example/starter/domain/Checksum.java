package com.example.starter.domain;

import java.time.Instant;

/**
 * 制品版本的内容校验摘要登记记录，登记后不可改写。
 *
 * @param artifactId    所属制品版本 ID
 * @param checksumValue 摘要原文，格式为 sha256: 加 64 位小写十六进制
 * @param registeredBy  登记操作者（发布角色标识）
 * @param createdAt     登记时间，UTC
 */
public record Checksum(
        long artifactId,
        String checksumValue,
        String registeredBy,
        Instant createdAt) {
}
