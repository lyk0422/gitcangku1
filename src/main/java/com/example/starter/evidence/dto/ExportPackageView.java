package com.example.starter.evidence.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 导出包视图：包标识与逐件证物的版本、摘要清单。重放同一 requestId 返回与首次完全一致的内容。
 *
 * @param packageKey 导出包业务标识
 * @param requestId  导出请求幂等键
 * @param items      包清单（证物键、版本、摘要快照），按证物键排序
 * @param createdAt  导出包生成时间（Asia/Shanghai）
 */
public record ExportPackageView(
        String packageKey,
        String requestId,
        List<ExportItemView> items,
        LocalDateTime createdAt) {
}
