package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 修订工时表读数请求：只改变该表内原始工时，不改采样时刻；作为历史保养锚点的读数不可修订（409）。
 * 已关闭表读数修订值不得超过该表 finalRawHours；若修订改变该表最后有效读数，则整链重算，
 * 重算产生倒退/跨表重叠/保养落到未来工时则 422 整体回滚。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param rawHours         修订后的表内原始工时（小时），非负
 */
public record ReviseReadingRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull @PositiveOrZero BigDecimal rawHours) {
}
