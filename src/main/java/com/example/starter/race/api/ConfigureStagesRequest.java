package com.example.starter.race.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 配置阶段赛事请求：尚无计时数据的赛事一次性固定 2~5 个阶段，
 * 每个阶段携带各自的有序检查点（阶段从零计时）。
 *
 * @param requestId       全局唯一请求ID（写操作幂等键）
 * @param operator        非空操作者标识，参与幂等身份摘要
 * @param expectedVersion 客户端所见赛事版本
 * @param stages          阶段定义列表，stageNo 从1连续递增
 */
public record ConfigureStagesRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotEmpty List<@Valid StageDefinition> stages
) {

    /**
     * 单个阶段定义。
     *
     * @param stageNo     阶段序号，从1连续递增
     * @param checkpoints 阶段内检查点，position 从1连续递增
     */
    public record StageDefinition(
            @NotNull @Positive Integer stageNo,
            @NotEmpty List<@Valid CheckpointDefinition> checkpoints
    ) {
    }

    /**
     * 阶段内检查点定义。
     *
     * @param checkpointCode 检查点代码，同一阶段内唯一
     * @param position       阶段内顺序，从1连续递增
     */
    public record CheckpointDefinition(
            @NotBlank String checkpointCode,
            @NotNull @Positive Integer position
    ) {
    }
}
