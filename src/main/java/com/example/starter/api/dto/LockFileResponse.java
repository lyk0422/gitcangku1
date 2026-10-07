package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 历史锁文件视图，entries 按名称升序排列；
 * coordinationHits 为锁定时冻结的协调组命中依据（未触发任何组时为空列表）。
 */
public record LockFileResponse(
        long id,
        String rootName,
        int rootVersion,
        long repositoryVersion,
        Instant createdAt,
        List<LockEntryResponse> entries,
        List<CoordinationHitView> coordinationHits) {

    /** 无协调组命中依据的兼容构造。 */
    public LockFileResponse(long id, String rootName, int rootVersion,
                            long repositoryVersion, Instant createdAt,
                            List<LockEntryResponse> entries) {
        this(id, rootName, rootVersion, repositoryVersion, createdAt, entries, List.of());
    }
}
