package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 历史锁文件视图，entries 按名称升序排列。
 *
 * @param operator    执行本次锁定的非空操作者
 * @param entries     普通依赖图中每个名称对应的唯一精确版本（含根）
 * @param peerMatches 锁定时刻冻结的全部 peer 声明及实际宿主匹配版本，
 *                    按声明方名称、peer 名称升序；后续撤回不改变旧锁
 */
public record LockFileResponse(
        long id,
        String rootName,
        int rootVersion,
        String operator,
        long repositoryVersion,
        Instant createdAt,
        List<LockEntryResponse> entries,
        List<LockPeerMatchResponse> peerMatches) {
}
