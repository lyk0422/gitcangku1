package com.example.starter.incident;

/**
 * 联合交接闭包事件行实体，对应 joint_handover_incidents 表（不可变）。
 * inSubmitted 标识该事件是否在发起提交集合中（闭包自动补全事件为 false）；
 * inClosure 固定为 true；seqNo 为按事件键排序后的闭包序号。
 */
public record JointHandoverIncident(
        long id,
        long handoverId,
        long incidentId,
        String incidentKey,
        boolean inSubmitted,
        boolean inClosure,
        int seqNo) {
}
