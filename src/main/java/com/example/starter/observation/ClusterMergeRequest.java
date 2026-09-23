package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 重复观测簇归并确认请求：审核人提交 clusterKey、完整成员集合（记录键+冻结 generation）、
 * 新主记录键及每个业务字段的来源成员键。成员集合的确定完全以本请求为准，不做后台自动聚类。
 *
 * @param requestId          全局唯一请求标识（幂等去重键）
 * @param clusterKey         审核人指定的簇标识，全局唯一，成功归并后不可复用
 * @param canonicalRecordKey 归并生成的新主记录（canonical）键，不得与既有观测记录键冲突
 * @param members            完整成员引用列表（2～20 条），顺序不影响幂等指纹
 * @param fieldSources       业务字段来源映射，键为 location/reading/note，值为簇内成员记录键；
 *                           每个字段必须恰好选择一次且来源必须在簇内（遗漏/多余/来源越界均 409）
 */
public record ClusterMergeRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String clusterKey,
        @NotBlank @Size(max = 64) String canonicalRecordKey,
        @NotEmpty @Valid @Size(min = 2, max = 20) List<ClusterMemberRef> members,
        @NotNull Map<String, String> fieldSources) {
}
