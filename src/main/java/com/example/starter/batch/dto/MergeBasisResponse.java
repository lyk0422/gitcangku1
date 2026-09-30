package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 合批子批冻结依据：固化的清洁代次、过敏原集合与各父批合批时快照。
 * 该依据在合批落定时写入，后续产线清洁推进或父批变化均不改写。
 */
public record MergeBasisResponse(
        int frozenCleanGeneration,
        List<String> frozenAllergens,
        List<MergeResponse.ParentBasis> parents,
        Instant mergedAt
) {
}
