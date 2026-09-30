package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 证物数字摘要见证视图：区分待见证/可导出/摘要异常，并保留见证人、版本与历史导出清单。
 *
 * @param evidenceKey   证物业务键
 * @param state         查询时刻摘要状态
 * @param currentVersionNo 当前 ACTIVE 版本号；无版本时为 null
 * @param currentSha256 当前封存版本登记摘要；无版本时为 null
 * @param currentContentSha256 当前内容重算摘要；尚未登记内容时为 null
 * @param versions      全部封存版本（含见证人，按版本号升序）
 * @param exports       历史导出清单（只追加，后续内容变化不改写）
 */
public record DigestStatusView(
        String evidenceKey,
        DigestState state,
        Integer currentVersionNo,
        String currentSha256,
        String currentContentSha256,
        List<DigestVersionView> versions,
        List<ExportPackageView> exports) {
}
