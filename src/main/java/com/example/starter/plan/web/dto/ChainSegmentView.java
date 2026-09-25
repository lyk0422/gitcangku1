package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 交路链明细中的一段（一张已发布计划）。linkToNext 为 null 表示链尾。
 */
public record ChainSegmentView(String scheduleKey, String status, String chainState,
                               String originStation, String destStation,
                               Instant departureUtc, Instant arrivalUtc,
                               ChainLinkView linkToNext) {
}
