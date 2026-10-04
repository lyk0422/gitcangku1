package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 批次上传中的单条有序消息：绑定设备、会话、正整数 seq 及一个观测创建或合并请求。
 *
 * @param deviceId      设备标识（合成设备）
 * @param sessionId     会话标识
 * @param seq           消息序号，正整数，按设备会话唯一
 * @param operation     观测操作类型：CREATE / MERGE
 * @param observationId 观测记录唯一标识
 * @param baseVersion   离线提交基线版本号；operation 为 MERGE 时必填，CREATE 时须为空
 * @param location      观测地点（候选）完整值
 * @param reading       观测读数（候选）完整值，十进制字符串，最多三位小数
 * @param note          观测备注（候选）完整值
 */
public record SessionMessageRequest(
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String sessionId,
        @NotNull @Min(1) Integer seq,
        @NotNull SessionOperation operation,
        @NotBlank @Size(max = 64) String observationId,
        @Min(1) Integer baseVersion,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note) {
}