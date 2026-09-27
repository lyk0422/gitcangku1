package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 历史锁文件视图，entries 与 deprecationWarnings 均按名称升序排列。
 *
 * @param createdAt           锁定（解析）时刻，UTC；重解析生成新锁文件时取重解析时刻
 * @param deprecationWarnings 锁定时刻固化的弃用告警与替代建议快照，之后不回写
 */
public record LockFileResponse(
        long id,
        String rootName,
        int rootVersion,
        long repositoryVersion,
        Instant createdAt,
        List<LockEntryResponse> entries,
        List<DeprecationWarningView> deprecationWarnings) {
}
