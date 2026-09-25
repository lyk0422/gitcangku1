package com.example.starter.calibration.api.dto;

/**
 * 重算请求。对未放行测量在一个事务内按新环境与当前生效系数版本生成新测量版本并重新评估整批；
 * 已放行结果及其系数快照不改写。原始读数与合格区间沿用首次提交。
 *
 * @param measurementKey  测量键
 * @param instrumentModel 仪器型号；可空，缺省沿用该测量首次提交的型号
 * @param temperature     新环境温度（摄氏度）
 * @param humidity        新环境相对湿度（%RH）
 * @param uncertainty     新测量不确定度（与读数同量纲），可空
 */
public record RecalculateRequest(
        String measurementKey,
        String instrumentModel,
        String temperature,
        String humidity,
        String uncertainty) {
}
