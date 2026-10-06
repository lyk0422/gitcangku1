package com.example.starter.race.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 配置赛事检查点请求（仅 OPEN 且尚无任何分段记录时可一次性配置，配置后不可修改）。
 *
 * @param checkpoints     1~20 个检查点，checkpointCode 赛事内唯一，position 从1连续递增
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record ConfigureCheckpointsRequest(
        @NotEmpty @Size(max = 20) @Valid List<CheckpointDefinition> checkpoints,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {

    /**
     * 单个检查点定义。
     *
     * @param checkpointCode 检查点代码，赛事内唯一
     * @param position       检查点顺序，从1连续递增
     * @param sensorMode     是否启用三源计时模式：true 时该点只能经传感器采样与裁定写入分段，
     *                       原单条计时入口拒绝；缺省 false
     */
    public record CheckpointDefinition(
            @NotBlank String checkpointCode,
            @NotNull @Positive Integer position,
            Boolean sensorMode
    ) {

        /** 兼容构造器：未声明三源模式时按普通单条计时检查点处理。 */
        public CheckpointDefinition(String checkpointCode, Integer position) {
            this(checkpointCode, position, Boolean.FALSE);
        }

        /** 归一化后的三源模式标记（null 视为 false）。 */
        public boolean sensorModeEnabled() {
            return Boolean.TRUE.equals(sensorMode);
        }
    }
}
