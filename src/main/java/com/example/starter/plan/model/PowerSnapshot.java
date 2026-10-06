package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 发布/改签裁决时冻结的功率快照：当时绑定、声明功率与上限版本，历史查询不漂移。
 *
 * @param id            主键（写入前为 0）
 * @param planId        所属计划 id
 * @param seq           占用在计划内的序号
 * @param sectionId     区段 ID
 * @param substationId  裁决时绑定变电所 id
 * @param substationKey 裁决时绑定变电所业务键
 * @param limitVersion  裁决时变电所上限版本
 * @param limitKw       裁决时功率上限，单位千瓦
 * @param powerKw       该占用声明功率，单位千瓦
 * @param startUtc      占用开始（含），UTC
 * @param endUtc        占用结束（不含），UTC
 */
public record PowerSnapshot(long id, long planId, int seq, String sectionId, long substationId,
                            String substationKey, int limitVersion, int limitKw, int powerKw,
                            Instant startUtc, Instant endUtc) {
}
