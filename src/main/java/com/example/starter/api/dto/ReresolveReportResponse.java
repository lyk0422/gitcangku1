package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 锁文件重解析报告视图（不可变快照）。
 *
 * @param conclusion       REPRODUCIBLE=新解析集合与原锁文件逐名称逐版本一致；
 *                         DRIFTED=可行但存在差异（diffs 逐名称给出）；
 *                         INFEASIBLE=无可行组合（diffs 给出阻塞项）
 * @param repositoryVersion 重解析时读取的仓库版本号
 * @param entries          新解析集合（名称升序）；INFEASIBLE 时为空
 * @param diffs            差异明细（名称升序）；REPRODUCIBLE 时为空，INFEASIBLE 时为阻塞项
 */
public record ReresolveReportResponse(
        long id,
        long lockFileId,
        long repositoryVersion,
        String conclusion,
        Instant createdAt,
        List<LockEntryResponse> entries,
        List<ReresolveDiffResponse> diffs) {
}
