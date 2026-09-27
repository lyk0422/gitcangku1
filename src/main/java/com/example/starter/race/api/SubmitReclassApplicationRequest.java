package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 提交组别重分申请请求；仅 OPEN 赛事、封榜前可提交，目标组别必须存在且未封榜。
 *
 * @param applicationId   申请ID，全局唯一
 * @param bib             申请选手参赛号
 * @param targetCategory  目标组别代码
 * @param reason          申请理由
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record SubmitReclassApplicationRequest(
        @NotBlank String applicationId,
        @NotBlank String bib,
        @NotBlank String targetCategory,
        @NotBlank String reason,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
