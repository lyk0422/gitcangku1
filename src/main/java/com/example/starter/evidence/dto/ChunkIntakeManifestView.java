package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 分块入库只读块清单：待接收记录视图 + 已接收块列表（按偏移升序）。
 *
 * @param intake 待接收记录视图
 * @param chunks 已接收块列表（不含块载荷）
 */
public record ChunkIntakeManifestView(
        ChunkIntakeView intake,
        List<IntakeChunkView> chunks) {
}
