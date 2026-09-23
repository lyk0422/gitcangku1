package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 联合裁决请求：必须提交簇内全部尚未解决的字段冲突，以及每条成员观测的 expectedVersion。
 *
 * <p>每个冲突可选择 LOCAL、REMOTE、BASE 或显式新值（EXPLICIT + value）；冲突项与成员版本的
 * 提交顺序不影响同参判定（服务端按观测标识与字段名规范化）。没有未决冲突的簇允许提交空决定列表。
 *
 * @param requestId        全局唯一请求标识（幂等去重键；同参重放首次快照，异参 409，失败不占键）
 * @param expectedVersions 每条成员观测的预期当前版本号；键为观测标识，遗漏或不匹配均拒绝
 * @param decisions        逐字段冲突决定，必须恰好覆盖簇内全部 OPEN 字段冲突，不得遗漏、多余或重复；无未决冲突时为空列表
 * @param operator         执行联合裁决的审核员标识
 */
public record ArbitrateBundleRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotEmpty @NotNull Map<String, Integer> expectedVersions,
        @NotNull @Valid List<FieldDecision> decisions,
        @NotBlank @Size(max = 128) String operator) {
}
