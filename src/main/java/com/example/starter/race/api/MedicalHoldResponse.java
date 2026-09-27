package com.example.starter.race.api;

/**
 * 医疗暂停开始响应。
 *
 * @param holdId    医疗暂停ID，全局唯一
 * @param raceId    所属赛事ID
 * @param bib       选手参赛号
 * @param startAt   暂停开始时刻，Unix毫秒时间戳（UTC），左闭端点
 * @param reason    暂停原因（已固化）
 * @param startedBy 登记暂停的医疗角色ID
 * @param createdAt 开始登记提交时刻，Unix毫秒时间戳（UTC）
 */
public record MedicalHoldResponse(
        String holdId,
        String raceId,
        String bib,
        long startAt,
        String reason,
        String startedBy,
        long createdAt
) {
}
