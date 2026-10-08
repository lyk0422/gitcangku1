package com.example.starter.crosssection;

import java.time.Instant;
import java.util.List;

/**
 * 断面水域面积成果响应：返回冻结的完整点列、水面高程、精确分数面积与展示值。
 * 历史成果的点列与面积永不重算；源点后续修改只影响源点自身的“源已更新”提示，不改变本响应数值。
 *
 * @param resultId        全局唯一成果标识
 * @param sectionNo       成果所属断面号
 * @param waterLevelMm    整数水面高程 H（毫米）
 * @param areaNumerator   精确面积分子（平方毫米）
 * @param areaDenominator 精确面积分母
 * @param areaText        HALF_UP 三位小数展示值
 * @param points          冻结点列（按提交顺序，横向严格递增）
 * @param operator        创建成果的操作者标识
 * @param createdAtUtc    成果创建时刻（UTC，ISO-8601）
 * @param sourceUpdated   历史查询时：任一点的源观测点当前版本已不同于冻结版本（仅展示提示，不重算）
 */
public record CrossSectionResultResponse(
        String resultId,
        String sectionNo,
        int waterLevelMm,
        String areaNumerator,
        String areaDenominator,
        String areaText,
        List<FrozenPoint> points,
        String operator,
        Instant createdAtUtc,
        boolean sourceUpdated) {

    /**
     * 冻结点：坐标与源版本在创建时固定。
     */
    public record FrozenPoint(
            int ordinal,
            String observationId,
            int sourceVersion,
            int xMm,
            int zMm) {

        static FrozenPoint of(CrossSectionResultPointRecord record) {
            return new FrozenPoint(record.ordinal(), record.sourceObservationId(),
                    record.sourceVersion(), record.xMm(), record.zMm());
        }
    }

    /**
     * 由成果记录、冻结点列与源更新提示组装响应。
     */
    public static CrossSectionResultResponse of(CrossSectionResultRecord record,
                                                List<CrossSectionResultPointRecord> pointRecords,
                                                boolean sourceUpdated) {
        return new CrossSectionResultResponse(
                record.resultId(),
                record.sectionNo(),
                record.waterLevelMm(),
                record.areaNumerator(),
                record.areaDenominator(),
                record.areaText(),
                pointRecords.stream().map(FrozenPoint::of).toList(),
                record.operator(),
                record.createdAtUtc(),
                sourceUpdated);
    }
}
