package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 数字摘要封存请求。仅当前保管人可封存；按内容字节登记 SHA-256 并产生新版本。
 *
 * @param commandKey    幂等命令键
 * @param contentRef    封存内容定位引用（如存储路径/对象键）
 * @param contentBase64 封存文件内容的 Base-64，服务端解码后计算 SHA-256
 */
public record DigestSealRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 512) String contentRef,
        @NotBlank String contentBase64) {
}
