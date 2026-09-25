package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量裁决一组冲线证据请求：同一计时组的多条证据一次性裁决。
 *
 * <p>裁决前校验：赛事未封榜；全部证据属于该赛事、状态为 PENDING、计时与请求一致；
 * 全部候选人（全部证据建议顺序的并集）仍有效；裁决后组内名次无重复。
 * 任一条件不满足返回422且本批次不产生任何部分变更。
 *
 * @param rulingId         裁决批次ID，全局唯一；裁决成功后写入不可变快照
 * @param finishTimeMs     本批次证据对应的相同计时（毫秒）
 * @param evidenceIds      本批次裁决的证据ID数组；不得为空、不得重复
 * @param orderedBibs      裁判给出的最终组内名次顺序（参赛号数组，第1个为该计时组第1名）；
 *                         必须恰好覆盖全部候选人，不得遗漏或重复
 * @param operator         裁决操作者标识
 * @param expectedVersion  客户端所见赛事版本
 * @param requestId        全局唯一请求ID（幂等键）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdjudicateEvidenceRequest(
        @NotBlank String rulingId,
        @NotNull @Positive Long finishTimeMs,
        @NotEmpty @Size(max = 200) List<@NotBlank String> evidenceIds,
        @NotEmpty @Size(max = 100) List<@NotBlank String> orderedBibs,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
