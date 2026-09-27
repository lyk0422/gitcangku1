package com.example.starter.blind.dto;

/**
 * 区组随机表版本视图：不暴露处理序列，仅展示版本链、容量、状态与名额信息。
 * 所有数量均为实际值；扩容守恒失败时错误响应另行给出要求值与差额。
 *
 * @param id              版本主键（分配记录固化引用）
 * @param experimentId    实验编号
 * @param blockNo         区组号
 * @param versionNo       版本号，从1开始
 * @param parentVersionNo 前驱版本号；v1 为 null
 * @param capacity        该版本区组容量，单位：个
 * @param treatmentCodes  处理代码集合（字典序升序逗号分隔）
 * @param status          ACTIVE / CLOSED
 * @param assignedCount   该版本已有分配数（含退组，退组不释放序号）
 * @param remaining       该版本可分配未分配名额，实际值；CLOSED 版本恒为 0
 * @param closedRemaining 该版本被关闭的未分配余量，实际值；ACTIVE 版本恒为 0
 * @param createdAt       版本生成时刻，Unix 毫秒，UTC
 * @param closedAt        关闭时刻，Unix 毫秒，UTC；null 表示仍 ACTIVE
 */
public record TableVersionView(
        long id,
        String experimentId,
        int blockNo,
        int versionNo,
        Integer parentVersionNo,
        int capacity,
        String treatmentCodes,
        String status,
        int assignedCount,
        int remaining,
        int closedRemaining,
        long createdAt,
        Long closedAt
) {
}
