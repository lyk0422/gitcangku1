package com.example.starter.cue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 操作员对某个提示点的执行回报；成功与有因失败都会记录，跳号请求会被 409 拒绝且不落库。
 *
 * @param seqNo        回报的提示点序号
 * @param success      是否执行成功；FALSE 时必须给出失败原因，且不推进已执行序号
 * @param failReason   失败原因，失败时非空，最长 255；成功时必须为空
 * @param requestId    客户端幂等键，同场次内唯一；同 requestId 同参重放原结果，异参返回 409
 * @param operatorCode 操作员标识，可空
 */
public record ReportCueRequest(
        @NotNull(message = "序号不能为空")
        @Min(value = 1, message = "序号必须从 1 开始")
        Integer seqNo,

        @NotNull(message = "执行结果不能为空")
        Boolean success,

        @Size(max = 255, message = "失败原因长度不能超过 255")
        String failReason,

        @NotBlank(message = "requestId 不能为空")
        @Size(max = 64, message = "requestId 长度不能超过 64")
        String requestId,

        @Size(max = 64, message = "操作员标识长度不能超过 64")
        String operatorCode) {
}
