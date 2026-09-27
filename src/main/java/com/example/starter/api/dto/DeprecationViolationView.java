package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 已生效弃用导致的锁定违规明细：制品、版本、替代与生效时刻，按名称、版本稳定排序。
 */
public record DeprecationViolationView(
        String name,
        int version,
        int replacementVersion,
        Instant effectiveAt) {
}
