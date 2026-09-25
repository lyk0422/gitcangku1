package com.example.starter.blind.dto;

/**
 * 替补记录视图：固化原参与者、新参与者、区组、分配序号与时刻。
 * 严禁包含处理代码 treatment、席位号 seatNo 等盲底内容。
 *
 * @param experimentId          实验编号
 * @param replaceKey            替补键
 * @param allocationId          被继承的分配序号
 * @param blockNo               区组号
 * @param originalParticipantId 被替补的原参与者编号（REPLACED 终态）
 * @param newParticipantId      替补新参与者编号
 * @param operatorActor         执行替补的操作者编号
 * @param replacedAt            替补时间，Unix 毫秒，UTC
 */
public record ReplacementView(
        String experimentId,
        String replaceKey,
        long allocationId,
        int blockNo,
        String originalParticipantId,
        String newParticipantId,
        String operatorActor,
        long replacedAt
) {
}
