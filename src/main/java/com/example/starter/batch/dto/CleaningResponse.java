package com.example.starter.batch.dto;

/**
 * 生产线清洁代次推进响应。previousCleanGeneration 为推进前代次，cleanGeneration 为推进后当前代次；
 * 已合批子批在 batch_merge 中固化的历史代次不受该操作影响。
 */
public record CleaningResponse(
        String batchKey,
        int previousCleanGeneration,
        int cleanGeneration
) {
}
