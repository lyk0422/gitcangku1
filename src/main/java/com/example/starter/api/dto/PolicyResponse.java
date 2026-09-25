package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 命名空间许可证策略视图，allowedLicenses 按字典序升序。
 *
 * @param version 当前策略版本号，修改时作为 expectedVersion 回传
 */
public record PolicyResponse(
        String namespace,
        long version,
        List<String> allowedLicenses,
        boolean rejectUnknown,
        Instant updatedAt) {
}
