package com.example.starter.race.api;

import com.example.starter.race.domain.PenaltyType;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 处罚信息响应；处罚只可新增或撤销，不覆盖历史。
 *
 * @param penaltyId 全局处罚ID
 * @param bib       参赛号
 * @param type      ADD_TIME / DISQUALIFY
 * @param amountMs  加时毫秒数；取消资格为 null
 * @param revoked   是否已撤销
 * @param version   处罚版本号，从1开始；撤销或裁决替换时加一
 * @param createdAt 新增时间，Unix毫秒时间戳
 * @param revokedAt 撤销时间，Unix毫秒时间戳；未撤销为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PenaltyResponse(
        String penaltyId,
        String bib,
        PenaltyType type,
        Long amountMs,
        boolean revoked,
        int version,
        long createdAt,
        Long revokedAt
) {
}
