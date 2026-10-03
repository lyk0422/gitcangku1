package com.example.starter.observation;

import java.time.Instant;

/**
 * 披露附页明细：发布后的更正记录，按 seq 升序排列，永不修改。
 *
 * @param observationId 观测记录唯一标识
 * @param seq           附页序号，每簇内从 1 开始单调递增
 * @param location      更正提交的观测地点完整值
 * @param reading       更正提交的观测读数完整值
 * @param note          更正提交的观测备注完整值
 * @param reason        更正原因说明
 * @param operator      提交更正的操作者标识
 * @param createdAtUtc  附页生成时刻（UTC）
 */
public record AppendixResponse(
        String observationId,
        int seq,
        String location,
        String reading,
        String note,
        String reason,
        String operator,
        Instant createdAtUtc) {
}