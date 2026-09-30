package com.example.starter.race.domain;

/**
 * 团队榜中的单个团队条目（实时或封榜快照统一形状）。
 *
 * @param teamId           团队ID
 * @param rosterVersionNo  引用的完整名单版本号（实时榜为当前最新锁定版本，封榜榜为封榜时引用版本）
 * @param teamPoints       团队积分：名单版本固化成员积分之和，剔除已退赛成员
 * @param memberCount      计入积分的成员人数（已剔除退赛成员）
 * @param rankNo           团队名次，从1开始；积分并列同名次并跳号（1、1、3）
 */
public record TeamStanding(
        String teamId,
        int rosterVersionNo,
        int teamPoints,
        int memberCount,
        int rankNo
) {
}
