package com.example.starter.evidence.dto;

/**
 * 只读块清单项：只暴露偏移与长度，不包含块载荷（载荷仅允许当前保管人通过取字节入口获取）。
 *
 * @param offset 块起始偏移（字节）
 * @param length 块解码后字节长度（1..4096）
 */
public record ChunkView(
        int offset,
        int length) {
}
