package com.example.starter.evidence.dto;

/**
 * 补块结果视图。完全相同范围同内容（含不同 requestId 的重传）返回同一视图，不新增块、不递增版本。
 *
 * @param offset  块起始偏移（字节）
 * @param length  块解码后字节长度（1..4096）
 * @param version 当前接收版本（已接收的非重复块数）
 */
public record ChunkUploadView(
        int offset,
        int length,
        int version) {
}
