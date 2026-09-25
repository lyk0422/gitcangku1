package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 储运偏差登记请求：一次可登记一条或多条偏差，任一非法整批回滚。
 *
 * @param excursions 偏差条目，至少 1 条；区间为 UTC 左闭右开 [startUtc, endUtc)
 */
public record RegisterExcursionsRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotEmpty(message = "excursions 至少包含一条偏差")
        @Valid List<ExcursionInput> excursions
) {

    /**
     * 单条储运偏差输入；严重级别由服务端按批次规格判定，不由调用方指定。
     *
     * @param minTempC 实测最低温，单位摄氏度
     * @param maxTempC 实测最高温，单位摄氏度
     */
    public record ExcursionInput(
            @NotBlank(message = "excursionKey 不能为空") String excursionKey,
            @NotNull(message = "startUtc 不能为空") Instant startUtc,
            @NotNull(message = "endUtc 不能为空") Instant endUtc,
            @NotNull(message = "minTempC 不能为空") BigDecimal minTempC,
            @NotNull(message = "maxTempC 不能为空") BigDecimal maxTempC
    ) {
    }
}
