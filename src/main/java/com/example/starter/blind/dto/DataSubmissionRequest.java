package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 采集数据提交请求体（合成观测数据）。
 *
 * @param payload 观测数据文本
 */
public record DataSubmissionRequest(
        @NotBlank(message = "payload 不能为空")
        @Size(max = 500, message = "payload 最长 500 字符")
        String payload
) {
}
