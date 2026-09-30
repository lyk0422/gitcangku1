package com.example.starter.evidence.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 导出包视图。包清单在导出成功时固化，后续内容变化不改写。
 *
 * @param packageId 导出包标识
 * @param requestId 导出请求幂等键
 * @param items     包内明细（按证物键排序），标明每件证物的版本、摘要与两名见证人
 * @param createdAt 导出时间（Asia/Shanghai）
 */
public record ExportPackageView(
        String packageId,
        String requestId,
        List<ExportItemView> items,
        LocalDateTime createdAt) {
}
