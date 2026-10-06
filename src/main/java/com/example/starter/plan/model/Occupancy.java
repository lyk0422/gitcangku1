package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 计划内一条区段占用，区间左闭右开 [startUtc, endUtc)。
 *
 * @param id                   主键
 * @param planId               所属计划 id
 * @param seq                  计划内序号，保持提交顺序
 * @param trainNo              列车编号
 * @param sectionId            区段 ID
 * @param startUtc             占用开始时刻（含），UTC
 * @param endUtc               占用结束时刻（不含），UTC，必须晚于 startUtc
 * @param direction            行车方向标识，须为区段单线配置的正/反方向之一；区段无配置时为 null
 * @param appliedConfigVersion 发布成功时冻结的区段配置版本；草稿或无配置区段为 null，取消不改写
 */
public record Occupancy(long id, long planId, int seq, String trainNo, String sectionId,
                        Instant startUtc, Instant endUtc, String direction,
                        Integer appliedConfigVersion) {
}
