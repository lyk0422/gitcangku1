package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 只读弃用诊断结果：在不创建锁文件、不改变任何状态的前提下，
 * 按指定解析时刻（缺省为当前时钟）对根制品依赖闭包做弃用裁决预演。
 */
public record DeprecationDiagnosisResponse(
        String rootName,
        int rootVersion,
        long repositoryVersion,
        Instant resolvedAt,
        List<LockEntryResponse> entries,
        List<DeprecationBlockedView> blocked,
        List<LockDeprecationWarningView> warnings) {
}
