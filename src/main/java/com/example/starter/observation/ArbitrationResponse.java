package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 联合裁决响应：不可变裁决记录要点及簇级裁决前/后快照（按观测标识稳定排序）。
 *
 * @param requestId       联合裁决请求标识（幂等键）
 * @param bundleKey       所属关联簇唯一标识
 * @param operator        执行裁决的审核员标识
 * @param arbitratedAtUtc 裁决完成时刻（UTC）
 * @param before          裁决前簇内全部观测快照
 * @param after           裁决后簇内全部观测快照（墓碑恢复项为存活内容，其余为裁决后新版本内容）
 * @param conflicts       本次裁决关闭的逐字段冲突（含来源、最终值与恢复依据），按观测、字段稳定排序
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ArbitrationResponse(
        String requestId,
        String bundleKey,
        String operator,
        Instant arbitratedAtUtc,
        List<ObservationResponse> before,
        List<ObservationResponse> after,
        List<FieldConflictResponse> conflicts) {
}
