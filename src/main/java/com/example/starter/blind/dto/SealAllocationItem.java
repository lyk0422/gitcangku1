package com.example.starter.blind.dto;

/**
 * 封存版本上的分配明细元素；只含版本固化信息与盲码视图，不含区内序号与处理代码。
 *
 * @param participantId 合成参与者编号
 * @param blindCode     随机无含义盲码
 * @param versionNo     分配时固化的随机表版本号；封存前历史分配为 null
 * @param sealId        分配时固化的封存编号；封存前历史分配为 null
 * @param status        ASSIGNED / WITHDRAWN
 * @param assignedAt    分配时间，Unix 毫秒，UTC
 * @param withdrawnAt   退组时间，Unix 毫秒，UTC；null 表示未退组
 */
public record SealAllocationItem(
        String participantId,
        String blindCode,
        Integer versionNo,
        String sealId,
        String status,
        long assignedAt,
        Long withdrawnAt
) {
}
