package com.example.starter.blind.dto;

/**
 * 中心激活确认响应视图。
 *
 * @param experimentId    所属实验编号
 * @param siteCode        中心编号
 * @param status          确认后的中心状态
 * @param generation      当前激活代次
 * @param activationKey   本次确认使用的 activationKey
 * @param firstActor      首次确认的操作者编号
 * @param firstConfirmedAt 首次确认时间，Unix 毫秒，UTC
 * @param secondActor     第二次确认的操作者编号；未产生时为 null
 * @param secondConfirmedAt 第二次确认时间，Unix 毫秒，UTC；未产生时为 null
 * @param activated       本次确认是否已完成双人激活（true=中心已 ACTIVE）
 */
public record ActivationConfirmView(
        String experimentId,
        String siteCode,
        String status,
        int generation,
        String activationKey,
        String firstActor,
        long firstConfirmedAt,
        String secondActor,
        Long secondConfirmedAt,
        boolean activated
) {
}
