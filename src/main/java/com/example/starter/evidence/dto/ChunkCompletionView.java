package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 显式完成成功响应：原子创建的原 SEALED 证物 + 只读块清单（按偏移升序）。
 *
 * @param evidence 新建 SEALED 证物视图（与原入库入口产物结构一致，保管人为入库人）
 * @param chunks   只读块清单，此后新块一律 409
 */
public record ChunkCompletionView(
        EvidenceView evidence,
        List<ChunkView> chunks) {
}
