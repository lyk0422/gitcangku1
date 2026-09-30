package com.example.starter.archival.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 建批请求。
 *
 * @param batchNo 脱酸批次号，全局唯一
 */
public record CreateBatchRequest(
        @NotBlank(message = "批次号不能为空")
        @Size(max = 64, message = "批次号长度不能超过64")
        String batchNo) {
}
