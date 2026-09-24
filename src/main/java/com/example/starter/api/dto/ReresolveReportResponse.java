package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 锁文件重解析报告视图，写入后不可变。
 *
 * @param id                报告 ID
 * @param reresolveKey      业务幂等键，全局唯一
 * @param lockFileId        原锁文件标识
 * @param rootName          原锁文件的根制品名称
 * @param rootVersion       原锁文件固定的根制品精确版本
 * @param repositoryVersion 本次重解析读取并固化的仓库版本号
 * @param conclusion        结论：REPRODUCIBLE/DRIFTED/INFEASIBLE
 * @param createdAt         报告生成时间，UTC 时间戳
 * @param newEntries        新解析集合（名称升序）；INFEASIBLE 时为空列表
 * @param diffs             逐名称差异或不可行原因明细（名称升序）
 */
public record ReresolveReportResponse(
        long id,
        String reresolveKey,
        long lockFileId,
        String rootName,
        int rootVersion,
        long repositoryVersion,
        String conclusion,
        Instant createdAt,
        List<LockEntryResponse> newEntries,
        List<ReresolveDiffResponse> diffs) {
}
