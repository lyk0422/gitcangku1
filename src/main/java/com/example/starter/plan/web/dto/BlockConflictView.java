package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 封锁冲突明细：计划占用与某个已生效封锁窗口在同区段上按 UTC 左闭右开相交。
 */
public record BlockConflictView(String blockKey, String sectionId,
                                Instant blockStartUtc, Instant blockEndUtc) {
}
