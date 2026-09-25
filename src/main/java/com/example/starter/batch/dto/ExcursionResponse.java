package com.example.starter.batch.dto;

import com.example.starter.batch.ExcursionSeverity;
import com.example.starter.batch.ExcursionStatus;

import java.time.Instant;

/**
 * 单条储运偏差响应。区间为 UTC 左闭右开 [startAt, endAt)；温度单位摄氏度；
 * severity 为按批次规格判定的严重级别；status 为裁决状态；
 * adjudication 为裁决后不可变快照，未裁决时为 null。
 */
public record ExcursionResponse(
        String excursionKey,
        String batchKey,
        Instant startAt,
        Instant endAt,
        double measuredMinTempC,
        double measuredMaxTempC,
        ExcursionSeverity severity,
        ExcursionStatus status,
        long batchVersion,
        ExcursionAdjudicationResponse adjudication,
        Instant registeredAt
) {
}
