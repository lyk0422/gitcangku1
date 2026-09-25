package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 储运偏差登记请求体。一次可登记 1～10 条偏差，先校验完整最终区间集合与规格判定，任一非法整批回滚。
 * 每条偏差给出 UTC 左闭右开区间 [startAt, endAt)、实测最低/最高温（摄氏度）；
 * 严重级别由服务按批次温度规格判定，不由调用方指定。
 */
public record RegisterExcursionsRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "excursions 不能为空")
        @Size(min = 1, max = 10, message = "excursions 必须包含 1～10 条偏差")
        List<@Valid ExcursionSpec> excursions
) {

    /**
     * 单条偏差规格；excursionKey 为批次内业务键，同键同参重放首次登记结果。
     */
    public record ExcursionSpec(
            @NotBlank(message = "excursionKey 不能为空") String excursionKey,
            @NotNull(message = "startAt 不能为空") Instant startAt,
            @NotNull(message = "endAt 不能为空") Instant endAt,
            @NotNull(message = "measuredMinTempC 不能为空") Double measuredMinTempC,
            @NotNull(message = "measuredMaxTempC 不能为空") Double measuredMaxTempC
    ) {
    }
}
