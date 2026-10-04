package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 确认罚时执行凭证请求；确认裁判必须与登记裁判不同。
 * 确认后按该处罚剩余未执行量抵扣，超出部分不产生负处罚。
 *
 * @param confirmedBy     确认裁判标识，必须与登记裁判不同
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record ConfirmPenaltyCreditRequest(
        @NotBlank String confirmedBy,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}