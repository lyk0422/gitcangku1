package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 墓碑恢复请求：expectedVersion 匹配当前墓碑版本时，以删除前最后一个有效版本内容生成新版本恢复观测。
 *
 * @param requestId       全局唯一请求标识（幂等去重键）
 * @param expectedVersion 期望恢复时的当前（墓碑）版本号
 */
public record RestoreObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer expectedVersion) {
}
