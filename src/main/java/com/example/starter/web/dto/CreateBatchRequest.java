package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建批次请求。
 *
 * @param batchNo 批次编号，合成编号，全局唯一
 */
public record CreateBatchRequest(
        @NotBlank(message = "batchNo 不能为空") @Size(max = 64) String batchNo) {
}