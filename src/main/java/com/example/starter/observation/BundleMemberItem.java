package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 建簇成员项：一条观测及其待三方比较的离线候选值。
 *
 * <p>墓碑观测只能作为待恢复项：建簇请求中墓碑成员不带候选值（location/reading/note/baseVersion 均为 null），
 * 服务端按墓碑登记 RESTORE 冲突，墓碑本身不提供任何候选值。
 *
 * @param observationId 成员观测记录唯一标识
 * @param baseVersion   存活成员离线修改所基于的基线版本号；墓碑成员为 null
 * @param location      存活成员离线候选地点完整值；墓碑成员为 null
 * @param reading       存活成员离线候选读数完整值（十进制原文，最多三位小数）；墓碑成员为 null
 * @param note          存活成员离线候选备注完整值；墓碑成员为 null
 */
public record BundleMemberItem(
        @NotBlank @Size(max = 64) String observationId,
        @Min(1) Integer baseVersion,
        @Size(max = 512) String location,
        @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @Size(max = 1024) String note) {

    /**
     * 是否携带存活成员的完整离线候选值（false 表示墓碑待恢复项）。
     */
    public boolean hasCandidate() {
        return baseVersion != null && location != null && reading != null && note != null;
    }

    /**
     * 候选值必须整体提供或整体省略：不允许只填部分字段。
     */
    public boolean candidatePartiallyProvided() {
        return !hasCandidate()
                && (baseVersion != null || location != null || reading != null || note != null);
    }
}
