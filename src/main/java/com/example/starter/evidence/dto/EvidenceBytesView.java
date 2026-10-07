package com.example.starter.evidence.dto;

/**
 * 证物完整字节视图。仅当前保管人可读取；借出及待交接期间保管人不变，按原归属限制。
 *
 * @param evidenceKey 证物业务键
 * @param totalBytes  总字节数
 * @param sha256      完整内容的 SHA-256 摘要（十六进制小写）
 * @param dataBase64  完整字节的 Base64 编码（合成数据）
 */
public record EvidenceBytesView(
        String evidenceKey,
        int totalBytes,
        String sha256,
        String dataBase64) {
}
