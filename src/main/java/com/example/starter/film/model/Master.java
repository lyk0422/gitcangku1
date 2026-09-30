package com.example.starter.film.model;

import java.time.LocalDateTime;

/**
 * 不可变固定母版。一旦生成，其引用的各片段版次与摘要快照不再受工作版更新影响。
 *
 * @param id              母版行ID
 * @param masterNo        母版编号，业务唯一且不可变，形如 {@code M-影片编号-审核ID}
 * @param filmId          所属影片ID
 * @param reviewId        来源审核请求ID
 * @param segmentCount    固定时的片段总数
 * @param createdAt       生成时间，Asia/Shanghai
 */
public record Master(
        Long id,
        String masterNo,
        Long filmId,
        Long reviewId,
        int segmentCount,
        LocalDateTime createdAt
) {
}
