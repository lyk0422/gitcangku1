package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 发布后更正请求：首次发布后的新更正只追加附页，不改写首次快照。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param operator  操作者标识
 * @param location  观测地点更正完整值
 * @param reading   观测读数更正完整值，十进制字符串，最多三位小数
 * @param note      观测备注更正完整值
 * @param reason    更正原因说明
 */
public record CorrectionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note,
        @NotNull @Size(max = 512) String reason) {
}