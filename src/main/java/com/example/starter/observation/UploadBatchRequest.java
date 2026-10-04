package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 离线终端观测批次上传请求：1 至 20 条有序消息，所有观测业务成功才整批保存。
 *
 * @param requestId 批次请求标识（幂等去重键）
 * @param operator  操作者标识
 * @param messages  有序消息列表，同一设备会话内 seq 不得重复
 */
public record UploadBatchRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 1, max = 20) List<@Valid SessionMessage> messages) {
}