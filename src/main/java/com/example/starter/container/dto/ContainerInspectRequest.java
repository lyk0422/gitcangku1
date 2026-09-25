package com.example.starter.container.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 容器巡检请求。允许提前巡检（inspectedAt 早于计划时刻）；
 * 下一次巡检时刻必须严格晚于实际时刻；FAIL 时 note 非空。
 *
 * @param commandKey       巡检幂等键 inspectKey，全局唯一；同键同参重放首次完整结果，失败不占键
 * @param containerVersion 发起时所见容器版本，参与巡检指纹；与当前版本不一致返回 409
 * @param inspectedAt      实际巡检时刻（UTC）
 * @param nextInspectionAt 巡检后下次巡检时刻（UTC），必须严格晚于 inspectedAt
 * @param result           封签结果：PASS / FAIL
 * @param note             巡检说明；FAIL 时不能为空
 */
public record ContainerInspectRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotNull Long containerVersion,
        @NotNull LocalDateTime inspectedAt,
        @NotNull LocalDateTime nextInspectionAt,
        @NotBlank String result,
        @Size(max = 512) String note) {
}
