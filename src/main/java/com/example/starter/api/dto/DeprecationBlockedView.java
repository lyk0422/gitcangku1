package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 锁定时命中已生效弃用窗口的阻断明细，按名称、版本稳定排序返回。
 */
public record DeprecationBlockedView(
        String name,
        int version,
        int replacementVersion,
        Instant effectiveAt) {
}
