package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 簇内冲突登记请求：成员观测的一次离线三方合并出现字段冲突时，提交候选全字段快照供联合裁决使用。
 * 同一观测以新候选再次登记时整组替换旧候选。
 *
 * @param requestId   全局唯一请求标识（幂等去重键）
 * @param observationId 冲突所在的簇成员观测标识
 * @param baseVersion 离线候选所基于的基线版本号
 * @param location    离线候选地点完整值
 * @param reading     离线候选读数完整值，十进制字符串，最多三位小数
 * @param note        离线候选备注完整值
 */
public record RegisterBundleConflictRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotNull @Min(1) Integer baseVersion,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note) {
}
