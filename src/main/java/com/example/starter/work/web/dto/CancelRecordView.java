package com.example.starter.work.web.dto;

import java.time.Instant;

/**
 * 施工单取消记录视图，不可变。
 */
public record CancelRecordView(
        String workKey,
        int version,
        String operator,
        Instant cancelledAt) {
}
