package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 见证大幅读数修订请求：另一名维护人见证申请指定的读数版本后，待确认值才生效。
 *
 * @param requestId          全局唯一请求标识（幂等键）
 * @param expectedVersion    设备期望版本号，与当前版本不一致时返回 409
 * @param witnessId          见证维护人标识，不可为空白，且不得与修订提交人相同
 * @param baseRevisionNo     要见证的读数版本（提交申请时的有效修订号）；
 *                           读数当前有效修订号已变化时返回 422，避免旧见证批准新读数
 */
public record WitnessReadingRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String witnessId,
        @NotNull @Positive Integer baseRevisionNo) {
}
