package com.example.starter.salinity.dto;

import java.util.List;

/**
 * 航次查询结果：原始样本（全部重测版本）、各位置当前采纳版本、冻结剖面（封存后非空）。
 *
 * @param voyageCode      航次编号
 * @param sealed          是否已封存
 * @param rawSamples      全部原始样本（含未采纳重测），按站位、深度、版本号升序
 * @param adoptedVersions 当前采纳版本，按站位、深度升序
 * @param sealedProfile   冻结剖面，按站位、深度升序；未封存为空列表
 * @param corrections     封存后追加的勘误，按追加先后升序
 */
public record VoyageDataView(String voyageCode,
                             boolean sealed,
                             List<SampleView> rawSamples,
                             List<SampleView> adoptedVersions,
                             List<SealedProfileView> sealedProfile,
                             List<CorrectionView> corrections) {
}
