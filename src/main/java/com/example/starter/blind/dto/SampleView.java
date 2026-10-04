package com.example.starter.blind.dto;

/**
 * 样本记录视图（协调员查询自己提交的记录）；不含盲码、席位与处理代码。
 *
 * @param sampleId       样本记录主键
 * @param experimentId   实验编号
 * @param participantId  合成参与者编号
 * @param phase          PRE / POST
 * @param sampleCode     样本编号，全实验唯一
 * @param digest         合成样本摘要
 * @param collectedAt    采集时刻，Unix 毫秒，UTC
 * @param chainVersion   链内版本号，首次登记为 1
 * @param supersedesId   被替代的最近有效样本记录主键；首次登记为 null
 * @param status         ACTIVE / SUPERSEDED
 * @param submittedActor 提交人操作者编号
 * @param createdAt      记录创建时间，Unix 毫秒，UTC
 */
public record SampleView(
        long sampleId,
        String experimentId,
        String participantId,
        String phase,
        String sampleCode,
        String digest,
        long collectedAt,
        int chainVersion,
        Long supersedesId,
        String status,
        String submittedActor,
        long createdAt
) {
}