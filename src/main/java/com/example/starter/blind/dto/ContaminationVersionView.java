package com.example.starter.blind.dto;

import java.util.List;

/**
 * 单个污染闭包版本的审计视图：不含处理代码。
 *
 * @param version      版本序号
 * @param status       版本状态 OPEN / CLOSED
 * @param edgeCount    该版本去重有向边数量
 * @param closure      该版本闭包快照（持密操作者编号升序）
 * @param createdAt    版本创建时间，Unix 毫秒，UTC
 * @param frozenAt     冻结时间，Unix 毫秒，UTC；未冻结为 null
 * @param quarantineId 关闭该版本的隔离单编号；未隔离为 null
 */
public record ContaminationVersionView(
        int version,
        String status,
        int edgeCount,
        List<String> closure,
        long createdAt,
        Long frozenAt,
        String quarantineId
) {
}
