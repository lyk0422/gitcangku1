package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 受试者数据提交请求体。
 *
 * @param accessGeneration 提交人持有的授权代次序号（旧代次令牌一律拒绝）
 * @param payload          合成数据内容，非真实医疗数据
 */
public record DataSubmitRequest(
        @NotNull(message = "accessGeneration 不能为空")
        Long accessGeneration,
        @NotBlank(message = "payload 不能为空")
        @Size(max = 1000, message = "payload 最长 1000 字符")
        String payload
) {
}
