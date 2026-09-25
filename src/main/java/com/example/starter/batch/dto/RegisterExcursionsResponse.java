package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 储运偏差登记响应：返回本次提交后批次版本号与本次登记的全部偏差（含服务判定的严重级别）。
 * 一次登记多条在同一事务内完成，任一非法整批回滚，不会出现部分写入。
 */
public record RegisterExcursionsResponse(
        String batchKey,
        long batchVersion,
        List<ExcursionResponse> excursions,
        Instant registeredAt
) {
}
