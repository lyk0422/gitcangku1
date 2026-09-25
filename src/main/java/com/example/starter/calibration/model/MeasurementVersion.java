package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量版本。提交生成 v1；对未放行测量重算时在同一事务内追加新版本并重新评估整批，
 * 已放行结果及其系数快照不可改写。
 *
 * @param id                    测量版本 ID（自增）
 * @param measurementId         所属测量记录 ID
 * @param versionNo             版本号，同一测量内从 1 起递增
 * @param temperature           该版本记录的环境温度（摄氏度）；未记录环境为 null
 * @param humidity              该版本记录的环境相对湿度（%RH）；未记录环境为 null
 * @param uncertainty           该版本测量不确定度（与读数同量纲）；未提供为 null
 * @param certificateId         该版本匹配到的校准证书 ID（标准器血缘快照）
 * @param compensationProfileId 该版本固化的补偿系数版本 ID；未补偿为 null
 * @param computedValue         该版本未舍入证书计算值 a×原始读数+b
 * @param compensatedValue      该版本补偿后测量值（HALF_UP 6 位小数）；未补偿为 null
 * @param passed                该版本证书计算值是否合格（含端点）
 * @param compensatedPassed     该版本补偿后是否合格（含端点）；未补偿为 null
 * @param parentVersionId       上一版本 ID；v1 为 null，构成重算链
 * @param calcKey               生成该版本的计算指纹键
 * @param createdAt             该版本生成时间（UTC）
 */
public record MeasurementVersion(
        long id,
        long measurementId,
        int versionNo,
        BigDecimal temperature,
        BigDecimal humidity,
        BigDecimal uncertainty,
        long certificateId,
        Long compensationProfileId,
        BigDecimal computedValue,
        BigDecimal compensatedValue,
        boolean passed,
        Boolean compensatedPassed,
        Long parentVersionId,
        String calcKey,
        Instant createdAt) {
}
