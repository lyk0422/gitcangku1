package com.example.starter.blind.dto;

import java.util.List;

/**
 * 隔离单视图：冻结某参与者在指定版本的完整污染闭包审计快照，不含处理代码。
 *
 * @param orderId        隔离单编号
 * @param experimentId   实验编号
 * @param participantId  参与者编号
 * @param version        被冻结的闭包版本号
 * @param status         OPEN=待另一名负责人确认；CLOSED=已确认关闭
 * @param initiatorActor 发起隔离单的合规负责人编号
 * @param confirmerActor 确认关闭的合规负责人编号；未确认时为 null
 * @param closureActors  发起时提交并冻结的闭包操作者快照（去重排序）
 * @param createdAt      发起时间，Unix 毫秒，UTC
 * @param confirmedAt    确认时间，Unix 毫秒，UTC；未确认为 null
 */
public record QuarantineOrderView(
        String orderId,
        String experimentId,
        String participantId,
        int version,
        String status,
        String initiatorActor,
        String confirmerActor,
        List<String> closureActors,
        long createdAt,
        Long confirmedAt
) {
}
