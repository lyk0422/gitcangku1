package com.example.starter.germination.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 作废一条取样检测记录请求。
 *
 * @param testNo     待作废检测编号
 * @param batchNo    所属批次编号（防止跨批次操作）
 * @param requestId  幂等请求号
 */
public record VoidTestRequest(@NotBlank String testNo,
                              @NotBlank String batchNo,
                              @NotBlank String requestId) {
}
