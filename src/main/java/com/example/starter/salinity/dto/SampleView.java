package com.example.starter.salinity.dto;

import com.example.starter.salinity.model.Sample;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 样本视图：用于原始样本列表与采纳版本展示。
 */
public record SampleView(Long id,
                         String voyageCode,
                         String station,
                         Integer depthM,
                         BigDecimal salinityPsu,
                         Instant collectedAtUtc,
                         String sampleNo,
                         boolean adopted,
                         Integer versionNo,
                         Instant createdAtUtc) {

    /**
     * 由领域模型构造视图。
     */
    public static SampleView of(Sample s) {
        return new SampleView(s.id(), s.voyageCode(), s.station(), s.depthM(), s.salinityPsu(),
                s.collectedAtUtc(), s.sampleNo(), s.adopted(), s.versionNo(), s.createdAtUtc());
    }
}
