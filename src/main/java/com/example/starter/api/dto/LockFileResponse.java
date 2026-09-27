package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 历史锁文件视图，entries 按名称升序，deprecationWarnings 按名称、版本升序。
 *
 * @param resolvedAt          锁定时固化的解析时刻（UTC）；历史锁此后不随新弃用改写
 * @param deprecationWarnings 锁定时刻命中的尚未生效弃用警告与替代建议（历史快照，可能为空列表）
 */
public record LockFileResponse(
        long id,
        String rootName,
        int rootVersion,
        long repositoryVersion,
        Instant createdAt,
        Instant resolvedAt,
        List<LockEntryResponse> entries,
        List<LockDeprecationWarningView> deprecationWarnings) {
}
