package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量设置弃用窗口结果：返回实际受理条数与要求条数，调用方无需猜测差额。
 *
 * @param requestedCount    请求要求写入的条数
 * @param acceptedCount     实际受理写入的条数（整批成功时与 requestedCount 相等）
 * @param repositoryVersion 整批提交后的仓库版本号
 */
public record DeprecationBatchResponse(
        Instant appliedAt,
        int requestedCount,
        int acceptedCount,
        long repositoryVersion,
        List<DeprecationWindowView> windows) {
}
