package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 分钟观测记录响应：普通版本携带全部业务字段；删除墓碑版本只返回删除状态和版本，不带业务字段。
 *
 * @param observationId 分钟观测记录唯一标识
 * @param version       版本号
 * @param deleted       是否为删除墓碑
 * @param stationId     站号（墓碑版本不返回）
 * @param minuteUtc     观测时刻（UTC 整分钟，ISO-8601；墓碑版本不返回）
 * @param reading       观测读数（墓碑版本不返回）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MinuteObservationResponse(
        String observationId,
        int version,
        boolean deleted,
        String stationId,
        Instant minuteUtc,
        String reading) {

    /**
     * 由快照构造响应；墓碑版本自动省略业务字段。
     */
    public static MinuteObservationResponse of(MinuteObservationSnapshot snapshot) {
        if (snapshot.deleted()) {
            return new MinuteObservationResponse(snapshot.observationId(), snapshot.version(), true,
                    null, null, null);
        }
        return new MinuteObservationResponse(snapshot.observationId(), snapshot.version(), false,
                snapshot.stationId(), snapshot.minuteUtc(), snapshot.reading());
    }
}
