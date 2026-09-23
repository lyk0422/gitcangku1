package com.example.starter.blind.dto;

import java.util.List;

/**
 * 闭包版本视图；不含处理代码。
 *
 * @param experimentId  实验编号
 * @param participantId 参与者编号
 * @param versionNo     版本号，从 1 递增
 * @param status        OPEN / CLOSED
 * @param actors        该版本闭包内操作者编号（有序去重）
 * @param edgeCount     该版本生成时的边总数
 * @param createdAt     版本生成时间，Unix 毫秒，UTC
 * @param closedAt      冻结时间，Unix 毫秒，UTC；OPEN 时为 null
 */
public record ClosureVersionView(
        String experimentId,
        String participantId,
        int versionNo,
        String status,
        List<String> actors,
        int edgeCount,
        long createdAt,
        Long closedAt
) {
}
