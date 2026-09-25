package com.example.starter.batch.dto;

import java.util.List;

/**
 * 复检缺口查询响应：当前 ACTIVE 召回上下文中，血缘闭包内每个批次仍缺合格复检的必做检验项。
 */
public record ReinspectionGapsResponse(
        String batchKey,
        int recallVersion,
        List<GapEntry> gaps
) {

    /**
     * 单个批次的复检缺口：missingItems 为空表示该批已完成合格复检。
     */
    public record GapEntry(
            String batchKey,
            List<String> missingItems
    ) {
    }
}
