package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 偏移登记/修改响应：返回偏移记录要点与同事务重建结果汇总。
 *
 * @param deviceId           采集设备唯一标识
 * @param effectiveFromUtc   偏移生效起始 UTC 时刻
 * @param offsetSeconds      变更后的偏移秒数
 * @param rebuiltSubmissions 本次重建中矫正后时刻发生变化的观测提交数
 * @param reorders           本次重建产生的不可变重排记录（胜出内容未变化时为空）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OffsetChangeResponse(
        String deviceId,
        Instant effectiveFromUtc,
        int offsetSeconds,
        int rebuiltSubmissions,
        List<ObservationReorder> reorders) {
}
