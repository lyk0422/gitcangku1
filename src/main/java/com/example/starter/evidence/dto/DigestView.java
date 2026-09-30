package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 证物数字摘要见证状态视图，用于区分待见证、可导出、摘要异常。
 *
 * @param evidenceKey    证物业务键
 * @param sealed         是否已封存登记内容（false 时其余版本字段为空）
 * @param versionNo      当前最新封存版本号；未封存为 null
 * @param contentSha256  当前封存版本登记的 SHA-256 摘要；未封存为 null
 * @param currentSha256  当前内容重算出的 SHA-256；未封存为 null
 * @param digestState    摘要状态：NOT_SEALED 尚未封存；
 *                       PENDING_WITNESS 待见证（当前版本见证少于两名不同保管人）
 *                       / EXPORTABLE 可导出（摘要匹配且两名见证齐备）
 *                       / DIGEST_MISMATCH 摘要异常（当前内容与封存版本摘要不符）
 * @param witnesses      全部历史见证（含已失效版本），按版本与提交顺序
 * @param exportPackages 涉及该证物的历史导出包标识，按生成顺序；后续内容变化不改写已导出包
 */
public record DigestView(
        String evidenceKey,
        boolean sealed,
        Integer versionNo,
        String contentSha256,
        String currentSha256,
        String digestState,
        List<WitnessView> witnesses,
        List<String> exportPackages) {
}
