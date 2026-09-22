package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 新增处罚请求。
 * type=ADD_TIME 时必须携带 1~3600000 毫秒的 amountMs；
 * type=DISQUALIFY 时不得携带 amountMs。
 *
 * @param penaltyId       处罚ID，全局唯一
 * @param bib             被罚选手参赛号
 * @param type            处罚类型：ADD_TIME / DISQUALIFY
 * @param amountMs        加时毫秒数；取消资格时为空
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddPenaltyRequest(
        @NotBlank String penaltyId,
        @NotBlank String bib,
        @NotBlank String type,
        @Positive Long amountMs,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
