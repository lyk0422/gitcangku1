package com.example.starter.domain;

/**
 * 单条可选依赖在锁定中的评估结果。
 *
 * @param sourceName    声明该可选依赖的已选制品名称
 * @param dependencyName 可选依赖的目标制品名称（即 {@link DependencyRange#name()}）
 * @param included      true=已纳入锁文件；false=跳过
 * @param targetVersion included 时目标制品的精确版本；skipped 时为 null
 * @param reason        skipped 时的稳定原因；included 时为 null
 */
public record OptionalDependencyOutcome(
        String sourceName,
        String dependencyName,
        boolean included,
        Integer targetVersion,
        String reason) {

    public static OptionalDependencyOutcome included(String sourceName, String dependencyName,
                                                     int targetVersion) {
        return new OptionalDependencyOutcome(sourceName, dependencyName, true, targetVersion, null);
    }

    public static OptionalDependencyOutcome skipped(String sourceName, String dependencyName,
                                                    String reason) {
        return new OptionalDependencyOutcome(sourceName, dependencyName, false, null, reason);
    }
}
