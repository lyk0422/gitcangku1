package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 站台到发占用登记行。发布/改签成功时在同一事务内写入，
 * 取消不删行（历史保留），生效性以来源计划状态 PUBLISHED 判定。
 *
 * @param id                主键
 * @param planId            登记来源计划 id
 * @param seq               对应计划内占用序号
 * @param trainNo           列车编号
 * @param platformId        停靠站台 ID
 * @param startUtc          到达占用开始时刻（含），UTC
 * @param endUtc            发车占用结束时刻（不含），UTC
 * @param capabilityVersion 登记时站台有效能力版本（历史快照，不随后续能力升级回写）
 * @param planStatus        来源计划当前状态（查询联表带出，决定该登记是否生效）
 */
public record PlatformBooking(long id, long planId, int seq, String trainNo, String platformId,
                              Instant startUtc, Instant endUtc, int capabilityVersion,
                              PlanStatus planStatus, String scheduleKey) {
}
