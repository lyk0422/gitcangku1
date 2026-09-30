package com.example.starter.archival.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 质检退回请求。退回理由只追加，旧理由与旧轮次读数均不覆盖。
 *
 * @param batchNo 批次号
 * @param reason  退回理由
 */
public record RejectRequest(
        @NotBlank(message = "批次号不能为空") String batchNo,
        @NotBlank(message = "退回理由不能为空")
        @Size(max = 500, message = "退回理由长度不能超过500")
        String reason) {
}
