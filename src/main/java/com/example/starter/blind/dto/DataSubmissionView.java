package com.example.starter.blind.dto;

/**
 * 数据提交结果视图；返回所归属代次，以验证“按事务提交顺序归属旧/新代次”。
 *
 * @param submissionId 提交记录主键
 * @param experimentId 实验编号
 * @param participantId 受试者编号
 * @param actorId      提交采集者
 * @param generationId 提交时活动代次主键
 * @param generationNo 提交时活动代次序号
 * @param payload      观测数据
 * @param submittedAt  提交时间，Unix 毫秒 UTC
 */
public record DataSubmissionView(
        long submissionId,
        String experimentId,
        String participantId,
        String actorId,
        long generationId,
        int generationNo,
        String payload,
        long submittedAt
) {
}
