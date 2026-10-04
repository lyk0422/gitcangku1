package com.example.starter.race.api;

import com.example.starter.race.domain.PenaltyType;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 处罚信息响应；处罚只可新增或撤销，不覆盖历史。
 * amountMs 为原罚时，servedMs 为已确认执行凭证的抵扣合计，二者独立返回，
 * 不通过改写原处罚秒数掩盖执行证据。
 *
 * @param penaltyId 全局处罚ID
 * @param bib       参赛号
 * @param type      ADD_TIME / DISQUALIFY
 * @param amountMs  加时毫秒数（原罚时）；取消资格为 null
 * @param servedMs  已确认执行凭证对该处罚的抵扣合计毫秒数；无执行为 0
 * @param revoked   是否已撤销
 * @param createdAt 新增时间，Unix毫秒时间戳
 * @param revokedAt 撤销时间，Unix毫秒时间戳；未撤销为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PenaltyResponse(
        String penaltyId,
        String bib,
        PenaltyType type,
        Long amountMs,
        long servedMs,
        boolean revoked,
        long createdAt,
        Long revokedAt
) {
}