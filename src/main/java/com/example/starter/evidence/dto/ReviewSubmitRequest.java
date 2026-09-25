package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 复核提交请求。仅批次保管人可提交，复核说明必填；
 * commandKey 为幂等键，同键同参重放返回首次结果。
 *
 * @param commandKey 幂等命令键
 * @param note       复核说明，必填，写入不可变复核记录
 */
public record ReviewSubmitRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 512) String note) {
}
