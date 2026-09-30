package com.example.starter.film.domain;

import java.time.Instant;

/**
 * 馆藏影片；按计划包含固定数量的片段，片段按 1..N 序号组成整部影片。
 *
 * @param id               影片主键
 * @param code             影片业务编号，全局唯一
 * @param plannedSegments  计划片段总数，审核时必须每段都有有效提交
 * @param createdAt        创建时间，UTC 时间线
 */
public record Film(long id, String code, int plannedSegments, Instant createdAt) {
}
