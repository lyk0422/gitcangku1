package com.example.starter.film.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 片段修复提交请求。
 *
 * @param segmentNo      片段序号，从 1 开始，不得超出影片计划片段数
 * @param contentDigest  内容摘要，格式必须为 {@code sha256:<64位十六进制>}
 * @param basedOnVersion 基于的当前版次；首版为 null，续版必须等于该片段提交前的最新版次
 * @param requestId      客户端幂等请求号
 */
public record SegmentSubmitRequest(
        @NotNull @Positive Integer segmentNo,
        @NotBlank
        @Pattern(regexp = "sha256:[0-9a-fA-F]{64}", message = "内容摘要必须为 sha256: 加 64 位十六进制")
        String contentDigest,
        Integer basedOnVersion,
        @NotBlank String requestId) {
}
