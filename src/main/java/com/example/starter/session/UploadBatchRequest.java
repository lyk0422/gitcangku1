package com.example.starter.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 设备会话消息上传批次请求：1 至 20 条有序消息，全部观测业务成功才整批保存。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param operator  操作者标识
 * @param deviceId  设备标识
 * @param sessionId 会话标识
 * @param messages  有序消息列表，批内 seq 不得重复
 */
public record UploadBatchRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String sessionId,
        @NotNull @Size(min = 1, max = 20) List<@Valid BatchMessage> messages) {
}