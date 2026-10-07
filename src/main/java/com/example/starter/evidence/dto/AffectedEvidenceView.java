package com.example.starter.evidence.dto;

/**
 * 污染传播路径中的受影响证物视图。
 *
 * @param evidenceKey    受影响证物业务键
 * @param sourceBatchKey 传播来源接触批次业务键；null 表示污染起点证物
 */
public record AffectedEvidenceView(
        String evidenceKey,
        String sourceBatchKey) {
}
