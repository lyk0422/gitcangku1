package com.example.starter.blind.dto;

import java.util.List;

/**
 * 隔离单视图；快照冻结审计快照，不含处理代码。
 *
 * @param orderId        隔离单编号
 * @param experimentId   实验编号
 * @param participantId  参与者编号
 * @param versionNo      发起时提交的版本号
 * @param initiatorActor 发起人
 * @param confirmerActor 确认人；OPEN 时为 null
 * @param status         OPEN / CONFIRMED
 * @param snapshotActors 发起时提交且校验通过的闭包快照操作者列表（有序）
 * @param edgeCount      发起时该参与者边总数
 * @param createdAt      发起时间，Unix 毫秒，UTC
 * @param confirmedAt    确认时间，Unix 毫秒，UTC；未确认为 null
 */
public record QuarantineOrderView(
        String orderId,
        String experimentId,
        String participantId,
        int versionNo,
        String initiatorActor,
        String confirmerActor,
        String status,
        List<String> snapshotActors,
        int edgeCount,
        long createdAt,
        Long confirmedAt
) {
}
