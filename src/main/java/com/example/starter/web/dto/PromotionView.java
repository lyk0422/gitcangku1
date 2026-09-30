package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 一次候补接替事件的视图。
 */
public record PromotionView(
        String cancelledRequestNo,
        String promotedRequestNo,
        String instrumentId,
        Instant gapStartAt,
        Instant gapEndAt,
        Instant promotedAt) {
}
