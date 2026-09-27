package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 摘要登记结果视图。
 *
 * @param checksum           规范化后的内容校验摘要（小写）
 * @param attestationVersion 该制品版本当前的见证版本号（每次见证登记或撤销加一）
 * @param registeredAt       摘要登记时间，UTC
 */
public record ChecksumResponse(
        String name,
        int version,
        String checksum,
        int attestationVersion,
        Instant registeredAt) {
}
