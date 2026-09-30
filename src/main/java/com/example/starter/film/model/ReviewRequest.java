package com.example.starter.film.model;

import java.time.LocalDateTime;

/**
 * 整片审核请求。创建时按序号固定每段引用的版次（基线），通过时据此生成母版。
 *
 * @param id             审核请求ID
 * @param filmId         所属影片ID
 * @param requestId      审核请求ID（幂等键），全局唯一
 * @param status         审核状态
 * @param masterId       通过后生成的母版ID；未通过或过期为 {@code null}
 * @param createdAt      创建时间，Asia/Shanghai
 * @param decidedAt      决定（通过/撤销）时间；未决定为 {@code null}
 */
public record ReviewRequest(
        Long id,
        Long filmId,
        String requestId,
        ReviewStatus status,
        Long masterId,
        LocalDateTime createdAt,
        LocalDateTime decidedAt
) {
}
