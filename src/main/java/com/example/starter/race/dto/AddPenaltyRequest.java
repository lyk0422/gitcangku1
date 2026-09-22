package com.example.starter.race.dto;

import com.example.starter.race.PenaltyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 新增处罚请求。
 *
 * @param requestId       全局唯一请求ID
 * @param penaltyId       处罚ID，全局唯一
 * @param bib             被处罚选手参赛号
 * @param type            处罚类型：TIME_ADD=加时，DISQUALIFY=取消资格
 * @param amountMs        加时毫秒数（1~3600000）；DISQUALIFY 类型必须为空
 * @param expectedVersion 期望的赛事当前版本，不一致返回409
 */
public record AddPenaltyRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String penaltyId,
        @NotBlank @Size(max = 64) String bib,
        @NotNull PenaltyType type,
        Long amountMs,
        @NotNull Long expectedVersion) {
}
