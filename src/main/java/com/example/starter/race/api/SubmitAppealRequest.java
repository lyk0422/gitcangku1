package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 提交处罚申诉请求。
 * 只能针对本人一条已生效且未申诉的处罚，在 finishAt（完赛耗时落库时间）后30分钟内提交。
 *
 * @param appealKey       申诉键，全局唯一
 * @param bib             申诉选手参赛号，必须等于被罚选手
 * @param penaltyId       被申诉处罚ID
 * @param penaltyVersion  申诉人所见处罚版本号；与当前版本不一致返回409
 * @param reason          申诉理由
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmitAppealRequest(
        @NotBlank String appealKey,
        @NotBlank String bib,
        @NotBlank String penaltyId,
        @NotNull @Positive Integer penaltyVersion,
        @NotBlank @Size(max = 1024) String reason,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
