package com.example.starter.film.domain;

import java.time.Instant;

/**
 * 整片审核生成的固定母版；母版编号一经生成不可变，不随后续工作版更新而变化。
 *
 * @param id          母版主键
 * @param filmId      所属影片 ID
 * @param masterNo    不可变母版编号，全局唯一，交付只引用该编号
 * @param status      母版状态
 * @param createdAt   审核通过时间，UTC 时间线
 * @param revokedAt   撤销时间，UTC 时间线；未撤销时为 null
 */
public record Master(long id, long filmId, String masterNo, MasterStatus status,
                     Instant createdAt, Instant revokedAt) {
}
