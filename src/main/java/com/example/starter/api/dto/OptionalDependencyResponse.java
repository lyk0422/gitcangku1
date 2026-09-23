package com.example.starter.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 锁文件中一条可选依赖的判定结果，按“来源名称、依赖名称”字典序排列。
 *
 * @param included        true=已纳入锁文件；false=跳过
 * @param selectedVersion 纳入时的精确版本；跳过时为 null
 * @param reason          跳过时的稳定原因；纳入时为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OptionalDependencyResponse(
        String sourceName,
        String dependencyName,
        int minimumVersion,
        int maximumVersion,
        boolean included,
        Integer selectedVersion,
        String reason) {
}
