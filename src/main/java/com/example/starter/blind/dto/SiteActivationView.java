package com.example.starter.blind.dto;

/**
 * 中心双人激活记录视图（不可变记录）。
 *
 * @param generation       本次激活产生的激活代次，从 1 开始
 * @param activationKey    两名确认人共同使用的 activationKey
 * @param firstActor       首次确认的操作者编号
 * @param firstConfirmedAt 首次确认时间，Unix 毫秒，UTC
 * @param secondActor      第二次确认的操作者编号，不同于首次确认人
 * @param secondConfirmedAt 第二次确认（激活生效）时间，Unix 毫秒，UTC
 * @param targetCap        激活时目标入组上限快照（人）
 */
public record SiteActivationView(
        int generation,
        String activationKey,
        String firstActor,
        long firstConfirmedAt,
        String secondActor,
        long secondConfirmedAt,
        int targetCap
) {
}
