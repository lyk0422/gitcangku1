package com.example.starter.blind.dto;

/**
 * 双人激活首确认后的暂存视图：等待第二名不同的未盲管理人员以相同 activationKey 确认。
 * 不回显 activationKey。
 *
 * @param state               固定为 AWAITING_SECOND_CONFIRMATION
 * @param experimentId        所属实验编号
 * @param siteCode            中心编号
 * @param generation          首确认时中心代次（首次激活为 0）
 * @param firstConfirmerActor 第一名确认的未盲管理人员编号
 * @param createdAt           首确认时间，Unix 毫秒，UTC
 */
public record SiteActivationPendingView(
        String state,
        String experimentId,
        String siteCode,
        int generation,
        String firstConfirmerActor,
        long createdAt
) {
}
