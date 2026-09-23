package com.example.starter.race.api;

import com.example.starter.race.domain.PenaltyType;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 处罚信息响应；处罚只可新增、撤销或经申诉 REPLACE 改判生成新版本，不覆盖历史。
 *
 * @param penaltyId           全局处罚ID
 * @param bib                 参赛号
 * @param type                ADD_TIME / DISQUALIFY
 * @param amountMs            加时毫秒数（普通加时1~3600000，REPLACE 替代罚时允许0）；取消资格为 null
 * @param version             处罚版本号，从1开始；REPLACE 裁决生成新版本
 * @param superseded          是否已被 REPLACE 新版本取代（历史版本保留、不参与计算）
 * @param supersedesPenaltyId 新处罚版本所取代的旧处罚ID；非替换产生为 null
 * @param revoked             是否已撤销
 * @param createdAt           新增时间，Unix毫秒时间戳
 * @param revokedAt           撤销时间，Unix毫秒时间戳；未撤销为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PenaltyResponse(
        String penaltyId,
        String bib,
        PenaltyType type,
        Long amountMs,
        int version,
        boolean superseded,
        String supersedesPenaltyId,
        boolean revoked,
        long createdAt,
        Long revokedAt
) {
}
