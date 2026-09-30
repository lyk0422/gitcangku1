package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 一条当前生效（所属计划已发布）的站台到发占用，站台管理方占用查询与冲突裁决共用。
 *
 * @param platformId          站台业务键
 * @param planId              所属计划 id
 * @param scheduleKey         所属计划业务键
 * @param trainNo             列车编号
 * @param sectionId           所属站场区段 ID
 * @param startUtc            占用开始时刻（含），UTC
 * @param endUtc              占用结束时刻（不含），UTC
 * @param consistLengthMeters 登记的编组长度，单位米
 * @param powerSystem         登记的编组供电制式
 * @param accessibleRequired  登记的无障碍需求是否提出，TRUE 需要
 * @param capabilityVersion   发布时固化的站台能力版本
 */
public record PlatformOccupancy(String platformId, long planId, String scheduleKey, String trainNo,
                                String sectionId, Instant startUtc, Instant endUtc,
                                int consistLengthMeters, String powerSystem,
                                boolean accessibleRequired, int capabilityVersion) {
}
