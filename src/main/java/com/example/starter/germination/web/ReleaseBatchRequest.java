package com.example.starter.germination.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 批次放行请求；仅可凭有效证书放行对应批次。
 *
 * @param batchNo    请求放行的批次编号
 * @param requestId  幂等请求号；同号同参重放返回首次放行结果，异参 409
 */
public record ReleaseBatchRequest(@NotBlank String batchNo,
                                  @NotBlank String requestId) {
}
