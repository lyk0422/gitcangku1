package com.example.starter.evidence.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 光照剂量账本明细视图。仅当前保管人可查；上限登记后不可变，
 * 累计剂量精确累加不舍入，显示值 HALF_UP 保留4位小数仅展示不参与判定。
 *
 * @param evidenceKey        关联证物业务键
 * @param limitLuxMinutes    不可逆光照上限（勒克斯分钟）
 * @param cumulativeLuxMin   累计光照剂量（勒克斯分钟），精确值
 * @param limitDisplay       上限显示值，HALF_UP 保留4位小数
 * @param cumulativeDisplay  累计显示值，HALF_UP 保留4位小数，仅展示不参与判定
 * @param doseBlocked        是否达到上限：true 表示 DOSE_BLOCKED，新增借出 422，交接不受影响
 * @param registeredBy       首次登记上限的保管人
 * @param version            账本当前版本：登记为 1，每次剂量归还 +1
 * @param returns            历史剂量归还（冻结的当次与累计剂量及剂量段），按发生顺序
 */
public record DoseLedgerView(
        String evidenceKey,
        BigDecimal limitLuxMinutes,
        BigDecimal cumulativeLuxMin,
        String limitDisplay,
        String cumulativeDisplay,
        boolean doseBlocked,
        String registeredBy,
        int version,
        List<DoseReturnView> returns) {
}
