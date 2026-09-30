package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 合批成功响应。子批初始 QUARANTINED，必做检验项为全部父批必做项的去重并集（稳定排序）；
 * frozenAllergens 为合批时全部父批共有的规范化过敏原集合快照，frozenCleaningGeneration
 * 为合批时全部父批共同使用的有效清洁代次——二者固化在子批与 batch_merge 中，
 * 后续父批清洁或任何变更都不改写该历史依据。mergedAt 为 UTC instant。
 */
public record MergeResponse(
        String childBatchKey,
        String childBatchNo,
        String productCode,
        BatchStatus childStatus,
        List<String> requiredTests,
        List<String> parentBatchKeys,
        List<String> frozenAllergens,
        Integer frozenCleaningGeneration,
        Instant mergedAt
) {
}
