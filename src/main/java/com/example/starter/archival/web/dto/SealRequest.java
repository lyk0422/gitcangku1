package com.example.starter.archival.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 封存请求。requestId 用于幂等：同参重放原封存结果（含上次失败原因），异参冲突 409。
 *
 * @param batchNo   批次号
 * @param requestId 封存幂等键
 */
public record SealRequest(
        @NotBlank(message = "批次号不能为空") String batchNo,
        @NotBlank(message = "requestId不能为空") String requestId) {
}
