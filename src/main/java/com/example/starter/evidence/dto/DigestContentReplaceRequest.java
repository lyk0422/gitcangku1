package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 当前文件内容替换请求。仅替换当前内容，不产生新封存版本、不改写已登记摘要：
 * 因此替换后导出会因重算摘要不符而返回 422；恢复为与封存版本一致的内容后可重新导出。
 *
 * @param commandKey    幂等命令键
 * @param contentRef    新内容定位引用（恢复时须与被见证版本的 contentRef 一致）
 * @param contentBase64 新文件内容的 Base-64
 */
public record DigestContentReplaceRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 512) String contentRef,
        @NotBlank String contentBase64) {
}
