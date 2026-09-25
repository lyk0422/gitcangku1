package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量创建冻结令请求：任一冲突或例外不全则整批 422，全部不写入。
 */
public record BatchCreateFreezeRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotEmpty List<@Valid CreateFreezeRequest> freezes) {
}
