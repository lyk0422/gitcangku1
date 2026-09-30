package com.example.starter.film.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 片段修复提交请求。
 *
 * @param seq           片段序号，从 1 开始
 * @param digest        内容摘要，4-128 位，允许字母数字与 . _ : -
 * @param baseVersion   基于的当前版次；首次提交传 0
 * @param requestId     幂等请求ID，非空，最长 64
 */
public record SegmentSubmitRequest(
        @NotNull @Min(1)
        Integer seq,

        @NotBlank
        @Size(min = 4, max = 128)
        @Pattern(regexp = "[A-Za-z0-9._:\\-]+", message = "摘要格式错误")
        String digest,

        @NotNull @Min(0)
        Integer baseVersion,

        @NotBlank @Size(max = 64)
        String requestId
) {
}
