package com.example.starter.blind.dto;

import java.util.List;

/**
 * 隔离单视图：提交闭包快照与版本，确认后冻结；不含处理代码。
 *
 * @param orderId        隔离单编号
 * @param experimentId   实验编号
 * @param participantId  被隔离参与者编号
 * @param version        发起时提交的版本序号
 * @param closure        发起时提交的完整闭包快照（持密操作者编号升序）
 * @param initiatorActor 发起隔离的合规负责人
 * @param confirmerActor 确认隔离的另一名合规负责人；未确认为 null
 * @param status         OPEN / CONFIRMED
 * @param createdAt      发起时间，Unix 毫秒，UTC
 * @param confirmedAt    确认时间，Unix 毫秒，UTC；未确认为 null
 */
public record QuarantineOrderView(
        String orderId,
        String experimentId,
        String participantId,
        int version,
        List<String> closure,
        String initiatorActor,
        String confirmerActor,
        String status,
        long createdAt,
        Long confirmedAt
) {
}
