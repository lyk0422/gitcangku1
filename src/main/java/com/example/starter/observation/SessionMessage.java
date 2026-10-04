package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 批次中的单条有序消息：绑定设备、会话、正整数序号及一个观测创建或合并请求。
 *
 * @param deviceId    设备标识
 * @param sessionId   会话标识
 * @param seq         会话内消息序号，正整数，按设备会话唯一
 * @param observation 观测创建或合并请求
 */
public record SessionMessage(
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String sessionId,
        @NotNull @Min(1) Integer seq,
        @NotNull @Valid ObservationCommand observation) {
}