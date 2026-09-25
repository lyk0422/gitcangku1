package com.example.starter.blind.dto;

/**
 * 不可变双人激活记录视图；不回显 activationKey。
 *
 * @param experimentId            所属实验编号
 * @param siteCode                中心编号
 * @param generation              激活代次
 * @param firstConfirmerActor     第一名确认的未盲管理人员编号
 * @param secondConfirmerActor    第二名确认的未盲管理人员编号
 * @param targetEnrollmentLimit   激活时中心目标入组上限快照（人）
 * @param activatedAt             第二人确认、激活完成时间，Unix 毫秒，UTC
 */
public record SiteActivationRecordView(
        String experimentId,
        String siteCode,
        int generation,
        String firstConfirmerActor,
        String secondConfirmerActor,
        int targetEnrollmentLimit,
        long activatedAt
) {
}
