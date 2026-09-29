package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 合格证放行门禁诊断：给出按当前 UTC 时点评估的实际值、要求值与差额，不改变任何状态。
 * 普通批次每个物料一项；合批产物按其全部父物料各一项。
 *
 * @param evaluatedAt           评估时点（UTC）
 * @param ready                 全部物料的合格证门禁是否满足放行要求
 * @param actualBindingCount    实际绑定物料数
 * @param requiredMaterialCount 要求覆盖的物料数
 * @param missingBindingCount   差额：要求数 - 实际数（未覆盖物料个数）
 * @param requiredMaterials     要求的物料集合（普通批次为其自身物料；合批产物为全部父物料），按字典序
 * @param actualBoundMaterials  实际已绑定物料集合，按字典序
 * @param missingMaterials      差额：要求但未绑定的物料集合，按字典序
 * @param items                 逐物料门禁评估明细，按物料字典序稳定排序
 */
public record CertificateDiagnosticsResponse(
        String batchKey,
        String batchNo,
        List<String> batchMaterialCodes,
        Instant evaluatedAt,
        boolean ready,
        int actualBindingCount,
        int requiredMaterialCount,
        int missingBindingCount,
        List<String> requiredMaterials,
        List<String> actualBoundMaterials,
        List<String> missingMaterials,
        List<MaterialGateItem> items
) {

    /**
     * 单个物料的门禁评估。时间差额用秒给出整数，精确时点以 validFrom/validTo/evaluatedAt 为准。
     *
     * @param declarationKey         实际绑定的声明键；未绑定为 null
     * @param version                实际绑定版本；未绑定为 null
     * @param declarationStatus      声明当前状态 ACTIVE/WITHDRAWN；未绑定为 null
     * @param matchedPrefix          实际命中的批号前缀；未命中为 null
     * @param materialMatches        批次物料与声明物料是否一致（要求值：一致）
     * @param prefixMatches          批号是否命中声明允许前缀（要求值：命中）
     * @param activeAtEvaluatedAt    声明在评估时点是否处于有效期且未撤回（要求值：true）
     * @param windowStartDeltaSeconds 差额实际值：evaluatedAt - validFrom 的秒数；要求 &gt;= 0（负数表示距生效还差 |x| 秒）
     * @param windowEndDeltaSeconds  差额实际值：validTo - evaluatedAt 的秒数；要求 &gt; 0（非正数表示已过期或恰在终点）
     * @param satisfied              该物料门禁是否全部满足
     * @param reasonCode             OK/NOT_BOUND/MATERIAL_MISMATCH/PREFIX_MISMATCH/DECLARATION_WITHDRAWN/NOT_YET_VALID/EXPIRED
     */
    public record MaterialGateItem(
            String materialCode,
            String declarationKey,
            Integer version,
            String declarationStatus,
            String matchedPrefix,
            boolean materialMatches,
            boolean prefixMatches,
            boolean activeAtEvaluatedAt,
            Long windowStartDeltaSeconds,
            Long windowEndDeltaSeconds,
            boolean satisfied,
            String reasonCode
    ) {
    }
}
