package com.example.starter.salinity.dto;

import com.example.starter.salinity.model.Correction;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 勘误视图。
 */
public record CorrectionView(Long id,
                             String station,
                             Integer depthM,
                             Long sealedProfileId,
                             BigDecimal correctedSalinityPsu,
                             String note,
                             Instant createdAtUtc) {

    /**
     * 由领域模型构造视图。
     */
    public static CorrectionView of(Correction c) {
        return new CorrectionView(c.id(), c.station(), c.depthM(), c.sealedProfileId(),
                c.correctedSalinityPsu(), c.note(), c.createdAtUtc());
    }
}
