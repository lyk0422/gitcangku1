package com.example.starter.blind.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 受限精确置换分析请求体。调用方不提供分组，处理映射取自数据库。
 *
 * @param participantIds 引用的 4~8 个不同未退组参与者编号；顺序无关，换序视为同参
 * @param resultVersion  引用的固定结果版本号，从 1 开始
 */
public record CreateAnalysisRequest(
        @NotNull(message = "participantIds 不能为空")
        List<String> participantIds,
        @NotNull(message = "resultVersion 不能为空")
        @Min(value = 1, message = "resultVersion 必须大于等于 1")
        Integer resultVersion
) {
}
