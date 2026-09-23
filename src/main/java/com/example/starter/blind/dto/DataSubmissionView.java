package com.example.starter.blind.dto;

/**
 * 受试者数据提交结果视图。
 *
 * @param id             数据主键
 * @param experimentId   实验编号
 * @param participantId  受试者编号
 * @param collectorActor 提交人
 * @param generationNo   写入归属的授权代次序号（按事务提交时活动代次确定）
 * @param submittedAt    提交时间，Unix 毫秒，UTC
 */
public record DataSubmissionView(
        long id,
        String experimentId,
        String participantId,
        String collectorActor,
        int generationNo,
        long submittedAt
) {
}
