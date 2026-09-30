package com.example.starter.salinity.dto;

import com.example.starter.salinity.model.SealedProfileEntry;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 冻结剖面单点视图。
 */
public record SealedProfileView(Long id,
                                String station,
                                Integer depthM,
                                Long sampleId,
                                String sampleNo,
                                BigDecimal salinityPsu,
                                Instant collectedAtUtc,
                                Instant sealedAtUtc) {

    /**
     * 由封存条目领域模型构造视图。
     */
    public static SealedProfileView of(SealedProfileEntry e) {
        return new SealedProfileView(e.id(), e.station(), e.depthM(), e.sampleId(), e.sampleNo(),
                e.salinityPsu(), e.collectedAtUtc(), e.sealedAtUtc());
    }
}
