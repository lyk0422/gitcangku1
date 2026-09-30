package com.example.starter.film.model;

import java.time.LocalDateTime;

/**
 * 馆藏影片。片段按 {@code plannedSegments} 个序号组成影片。
 *
 * @param id               影片ID
 * @param code             影片编号，业务唯一
 * @param title            影片标题
 * @param plannedSegments  计划片段总数（序号 1..N），无单位
 * @param createdAt        创建时间，Asia/Shanghai
 */
public record Film(
        Long id,
        String code,
        String title,
        int plannedSegments,
        LocalDateTime createdAt
) {
}
