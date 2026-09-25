package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 赛道纪录认定申请请求。
 * 计时不由客户端提交，认定事务内从封榜快照读取该选手最终总耗时。
 *
 * @param recordClaimKey 纪录认定申请键，全局唯一；重复申请按此键幂等返回首次结果
 * @param raceId         产生该计时的赛事ID（须已封榜且属于该赛道）
 * @param bib            选手参赛号（须在封榜快照中已完赛且未取消资格）
 * @param requestId      全局唯一请求ID（幂等键）
 */
public record ClaimRecordRequest(
        @NotBlank String recordClaimKey,
        @NotBlank String raceId,
        @NotBlank String bib,
        @NotBlank String requestId
) {
}
