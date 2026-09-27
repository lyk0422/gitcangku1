package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 历史锁文件视图，entries 按名称升序排列。
 *
 * @param createdAt 锁定解析时刻（UTC），锁定成功即固化，后续弃用变更不改写
 * @param deprecationWarnings 锁定时刻命中的弃用警告与替代建议快照，按名称升序
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
