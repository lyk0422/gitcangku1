package com.example.starter.crosssection;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 断面水域面积成果创建请求：引用同一断面 2 至 10 个当前版本点及整数水面高程 H。
 *
 * <p>点列按提交顺序给出，服务端核验其横向坐标严格递增；不会自行排序后接受原非法顺序。
 * 每个引用携带期望版本（乐观锁）；身份信息（操作者、操作、资源、全参数、期望版本）全部进入指纹防碰撞。
 *
 * @param requestId    全局唯一请求标识（幂等去重键，失败不占键）
 * @param resultId     全局唯一成果标识
 * @param waterLevelMm 整数水面高程 H（毫米）
 * @param points       源点引用列表，2 至 10 条，顺序即积分顺序
 * @param operator     非空操作者标识
 */
public record CreateCrossSectionResultRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String resultId,
        @NotNull Integer waterLevelMm,
        @NotNull @Size(min = 2, max = 10) @Valid List<SourcePointRef> points,
        @NotBlank @Size(max = 128) String operator) {

    /**
     * 源点引用：观测点标识与创建时期望的当前版本；版本不匹配返回 409。
     *
     * @param observationId   断面观测点唯一标识
     * @param expectedVersion 期望的源点当前版本号
     */
    public record SourcePointRef(
            @NotBlank @Size(max = 64) String observationId,
            @NotNull @jakarta.validation.constraints.Min(1) Integer expectedVersion) {
    }
}
