package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 联合裁决请求：必须提交簇内全部尚未解决的字段冲突选择及每条观测的 expectedVersion；
 * 恢复墓碑时同时给出该墓碑全部必填字段的来源。观测与字段项换序视为同参。
 *
 * <p>choices 允许为空：当簇内仅含待恢复墓碑、尚无存活观测冲突登记时，联合裁决只做墓碑恢复。
 *
 * @param requestId        全局唯一请求标识（幂等去重键）
 * @param operator         执行联合裁决的审核员标识
 * @param expectedVersions 簇内每条观测提交时的预期当前版本（必须覆盖簇全部成员，不得重复）
 * @param choices          全部未解决字段冲突的选择（必须恰好覆盖 OPEN 冲突，不得遗漏/多余/重复；可为空列表）
 * @param restores         墓碑恢复指令；仅待恢复成员需要，恢复必填字段来源为 BASE 或 VALUE
 */
public record ArbitrateBundleRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotEmpty @Valid List<BundleExpectedVersion> expectedVersions,
        @NotNull @Valid List<BundleConflictChoice> choices,
        @NotNull @Valid List<BundleRestoreInstruction> restores) {
}
