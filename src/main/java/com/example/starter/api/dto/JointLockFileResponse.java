package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 多根联合锁文件视图：roots 与 entries 按名称升序，edges 按 fromName、toName 升序。
 *
 * @param repositoryVersion 锁定时读取的仓库版本号
 */
public record JointLockFileResponse(
        long id,
        List<RootView> roots,
        long repositoryVersion,
        Instant createdAt,
        List<LockEntryResponse> entries,
        List<EdgeView> edges) {
}
