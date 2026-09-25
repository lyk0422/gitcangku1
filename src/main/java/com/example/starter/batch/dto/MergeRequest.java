package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批量合批请求。targetBatchKey 为路径参数之外的目标容器批次；
 * sources 为 1～10 个来源批次（另起方法支持单来源场景共用同一事务）；
 * compatibleLevels 为目标容器声明的兼容矩阵：只有不同隔离级别出现在矩阵中的两个级别
 * 才允许合并；同级别无需声明。
 * 先校验全部目标状态与矩阵兼容性，任一不兼容整批 422 并回滚全部血缘与库存。
 */
public record MergeRequest(
        @NotBlank(message = "allergenKey 不能为空") String allergenKey,
        @NotBlank(message = "targetBatchKey 不能为空") String targetBatchKey,
        @NotNull(message = "sources 不能为空")
        @NotEmpty(message = "sources 至少包含一个来源批次")
        @Valid List<MergeSource> sources,
        @Valid List<LevelPair> compatibleLevels
) {

    /**
     * 合批来源：来源批次键与并入数量（必须为正）。
     */
    public record MergeSource(
            @NotBlank(message = "来源 batchKey 不能为空") String batchKey,
            @NotNull(message = "quantity 不能为空")
            @Positive(message = "quantity 必须为正数") BigDecimal quantity
    ) {
    }

    /**
     * 兼容矩阵中的无序级别对；两个级别不同才有意义，相同级别返回 400。
     */
    public record LevelPair(
            @NotNull(message = "兼容对 fromLevel 不能为空") SegregationLevelRef fromLevel,
            @NotNull(message = "兼容对 toLevel 不能为空") SegregationLevelRef toLevel
    ) {
    }

    /**
     * 兼容矩阵级别引用，用字符串接收以便在业务层给出 422 诊断而非 400 反序列化错误；
     * 合法值为 NONE/LOW/MEDIUM/HIGH。
     */
    public record SegregationLevelRef(String level) {
    }
}
