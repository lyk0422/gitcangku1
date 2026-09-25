package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 登记冲线证据请求：为相同计时（finishTimeMs）的一组候选人登记一条冲线证据。
 *
 * @param evidenceId       证据ID，全局唯一不可重复
 * @param finishTimeMs     证据对应的相同计时（毫秒，1~86400000）
 * @param suggestedOrder   建议顺序（参赛号数组）；候选人不得遗漏或重复，且必须全部为该计时组的有效选手
 * @param operator         登记操作者标识
 * @param capturedAt       证据捕获UTC时刻（Unix毫秒时间戳）；由调用方提供，服务端不做时钟改写
 * @param expectedVersion  客户端所见赛事版本
 * @param requestId        全局唯一请求ID（幂等键）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterEvidenceRequest(
        @NotBlank String evidenceId,
        @NotNull @Positive Long finishTimeMs,
        @NotEmpty @Size(max = 100) List<@NotBlank String> suggestedOrder,
        @NotBlank String operator,
        @NotNull Long capturedAt,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
