package com.example.starter.salinity.view;

import java.time.Instant;
import java.util.List;

/**
 * 航次完整查询视图：原始样本、当前采纳版本与冻结剖面一并返回。
 *
 * @param voyageCode      航次编号
 * @param sealed          是否已封存
 * @param sealedUtc       封存 UTC 时刻；未封存为 {@code null}
 * @param samples         全部原始样本（含历次重测），按入库先后排列
 * @param adopted         当前采纳版本，按站位、深度升序排列
 * @param frozenProfile   冻结剖面点，封存后按站位、深度（0、50、100）升序排列；未封存为空
 * @param errata          封存后追加的勘误，按追加先后排列
 */
public record VoyageDataView(
        String voyageCode,
        boolean sealed,
        Instant sealedUtc,
        List<SampleView> samples,
        List<AdoptedSampleView> adopted,
        List<FrozenProfilePointView> frozenProfile,
        List<ErratumView> errata
) {
}
