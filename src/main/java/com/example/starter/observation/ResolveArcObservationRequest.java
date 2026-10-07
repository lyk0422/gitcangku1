package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 圆弧观测人工裁决请求：强制写入候选圆弧形集合并清除未解决冲突标记。
 *
 * @param requestId              全局唯一请求标识（幂等去重键）
 * @param expectedCurrentVersion 客户端预期的裁决前当前版本号，不匹配返回 409
 * @param operator               操作者标识（非空）
 * @param arcs                   裁决后的圆弧集合完整值，1 至 16 条，集合顺序不影响比较
 */
public record ResolveArcObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer expectedCurrentVersion,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 1, max = 16) List<ArcComponent> arcs) {
}
