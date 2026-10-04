package com.example.starter.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 上传批次中的单条有序消息：绑定会话内序号与一条观测命令。
 *
 * @param seq     会话内消息序号，1..100，按（设备，会话）唯一，可乱序抵达
 * @param command 观测创建或合并命令
 */
public record BatchMessage(
        @NotNull @Min(1) @Max(100) Integer seq,
        @NotNull @Valid ObservationCommand command) {
}