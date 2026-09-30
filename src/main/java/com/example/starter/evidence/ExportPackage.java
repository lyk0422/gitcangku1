package com.example.starter.evidence;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 导出包实体，对应 export_package 表。包只追加、不可变；同 requestId 重放返回首次包。
 *
 * @param id            主键
 * @param packageId     导出包业务标识，全局唯一
 * @param requestId     导出请求幂等键
 * @param evidenceKey   包内排序后第一件证物键（反范式索引列）
 * @param versionNo     首件证物导出版本号（反范式列）
 * @param contentSha256 首件证物核对通过的 SHA-256（反范式列）
 * @param witness1      各明细见证人一致时的第一名见证人；null 表示不一致
 * @param witness2      各明细见证人一致时的第二名见证人；null 表示不一致
 * @param items         包内明细（组装视图时填充，表中无此列）
 * @param createdAt     导出时间（Asia/Shanghai）
 */
public record ExportPackage(
        Long id,
        String packageId,
        String requestId,
        String evidenceKey,
        int versionNo,
        String contentSha256,
        String witness1,
        String witness2,
        List<ExportItem> items,
        LocalDateTime createdAt) {
}
