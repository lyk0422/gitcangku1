package com.example.starter.api.dto;

/**
 * 重解析差异/不可行原因明细，按名称升序稳定排列。
 *
 * @param name            差异涉及的制品名称
 * @param changeType      变化类型：ADDED=新增，REMOVED=移除，VERSION_CHANGED=版本变化，
 *                        INFEASIBLE=导致不可行的名称
 * @param originalVersion 原锁定版本；新增或不可行时为 null
 * @param newVersion      新解析版本；移除或不可行时为 null
 * @param reason          原因代码：ORIGINAL_WITHDRAWN/SUPERSEDED_BY_HIGHER/
 *                        RANGE_NO_LONGER_SATISFIED，不可行时为
 *                        VERSIONS_WITHDRAWN/VERSIONS_MISSING/RANGE_INTERSECTION_EMPTY
 * @param detail          人类可读的稳定说明（含缺失或已撤回版本等）
 */
public record ReresolveDiffResponse(
        String name,
        String changeType,
        Integer originalVersion,
        Integer newVersion,
        String reason,
        String detail) {
}
