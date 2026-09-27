package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量标签修改请求：所有项先核对最终状态，再一次事务提交；任一失败整批回滚、不留半成品。
 *
 * @param requestId 全局唯一请求ID，用于同参重放
 * @param operator  操作者标识，纳入幂等指纹
 * @param items     修改项列表，至少一项；每项为设备标签全集覆盖
 */
public record BatchTagsRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotEmpty @Size(max = 500, message = "单批最多500项") @Valid List<BatchTagItemRequest> items) {
}
