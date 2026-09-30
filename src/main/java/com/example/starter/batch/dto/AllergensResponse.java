package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 过敏原保存结果：返回规范化（trim、去重、字典序稳定排序）后的过敏原集合与当前清洁代次。
 */
public record AllergensResponse(
        String batchKey,
        List<String> allergens,
        int cleaningGeneration,
        Instant updatedAt
) {
}
