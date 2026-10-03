package com.example.starter.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 登记花粉批次请求。
 *
 * @param batchNo 花粉批号，全局唯一
 */
public record CreatePollenBatchRequest(@NotBlank String batchNo) {
}