package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建观测更正附录请求：仅允许对已完成冲突裁决且裁决后未漂移的观测簇，逐字段提交更正。
 *
 * @param requestId               全局唯一请求标识（幂等去重键）
 * @param appendixId              全局唯一附录记录标识
 * @param resolutionId            前置冲突裁决记录标识，必须为该观测最新裁决
 * @param expectedAppendixVersion 客户端预期的当前附录版本号：0 表示尚无附录；争用同一前置版本时后到者收到 409
 * @param fieldName               更正字段名：location / reading / note，一次附录仅允许更正一个字段
 * @param newValue                更正后新值完整原文；不允许为空白（不允许删除字段），读数须为最多三位小数的十进制字符串
 * @param operator                提交更正附录的观测记录员标识
 */
public record CreateAppendixRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String appendixId,
        @NotBlank @Size(max = 128) String resolutionId,
        @NotNull @Min(0) Integer expectedAppendixVersion,
        @NotBlank @Size(max = 16) String fieldName,
        @NotNull @Size(max = 1024) String newValue,
        @NotBlank @Size(max = 128) String operator) {
}
