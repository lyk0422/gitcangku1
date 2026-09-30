package com.example.starter.germination.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 签发发芽率证书请求；依据该批次最近两次有效检测。
 *
 * @param batchNo    所属种子批次编号
 * @param requestId  幂等请求号；同号同参重放返回首次证书，异参 409
 */
public record IssueCertificateRequest(@NotBlank String batchNo,
                                      @NotBlank String requestId) {
}
