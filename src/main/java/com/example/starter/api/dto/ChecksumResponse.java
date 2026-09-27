package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 内容校验摘要视图。
 *
 * @param value             摘要原文；尚未登记时为 null
 * @param registeredBy      登记操作者（发布角色）；尚未登记时为 null
 * @param registeredAt      登记时间，UTC；尚未登记时为 null
 * @param repositoryVersion 该次写操作完成后的仓库版本号
 */
public record ChecksumResponse(
        String value,
        String registeredBy,
        Instant registeredAt,
        long repositoryVersion) {
}
