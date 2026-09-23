package com.example.starter.api.dto;

/**
 * 锁文件中单条可选依赖的评估结果视图，按“来源名称、依赖名称”字典序排列。
 *
 * @param included      true=已纳入锁定集合；false=因不满足条件被跳过
 * @param targetVersion included 时的精确版本；skipped 时为 null
 * @param reason        skipped 时的稳定原因；included 时为 null
 */
public record OptionalDependencyView(
        String sourceName,
        String dependencyName,
        boolean included,
        Integer targetVersion,
        String reason) {
}
