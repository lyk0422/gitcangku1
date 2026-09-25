package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 赛道纪录认定请求：申请把某已封榜赛事中某选手的最终计时认定为赛道纪录。
 *
 * @param raceId         产生计时的赛事ID（须已封榜）
 * @param bib            选手参赛号（须在封榜快照中且未取消资格）
 * @param recordClaimKey 认定申请键，全局唯一；同键重复申请幂等返回首次结果
 * @param requestId      全局唯一请求ID（幂等键，同键同参重放、异参409）
 */
public record ClaimRecordRequest(
        @NotBlank String raceId,
        @NotBlank String bib,
        @NotBlank String recordClaimKey,
        @NotBlank String requestId
) {
}
