package com.example.starter.web.dto;

import com.example.starter.model.SuccessionEvent;

import java.time.Instant;

/**
 * 候补接替流水视图，说明每次空档的来源与接替者。
 */
public record SuccessionView(
        String instrumentId,
        String vacatedRequestNo,
        String promotedRequestNo,
        Instant gapStart,
        Instant gapEnd,
        Instant createdAt
) {

    public static SuccessionView of(SuccessionEvent e) {
        return new SuccessionView(
                e.getInstrumentId(),
                e.getVacatedRequestNo(),
                e.getPromotedRequestNo(),
                e.getGapStart(),
                e.getGapEnd(),
                e.getCreatedAt()
        );
    }
}
