package com.example.starter.evidence.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 剂量账本明细视图。仅当前保管人可查；显示值为 HALF_UP 四位小数，不参与判定。
 *
 * @param evidenceKey          关联证物业务键
 * @param capLuxMinutes        不可逆光照上限（勒克斯分钟），登记后不可变
 * @param cumulativeLuxMinutes 累计剂量（勒克斯分钟），精确累加不舍入
 * @param cumulativeDisplay    累计剂量显示值，HALF_UP 保留4位小数，仅用于展示不参与判定
 * @param doseBlocked          累计剂量是否已达上限（达到即禁止新增借出）
 * @param version              账本版本：登记上限为1，每次剂量归还+1
 * @param registeredBy         登记上限时的保管人
 * @param returns              全部剂量归还记录（按发生顺序），历史冻结
 */
public record DoseLedgerView(
        String evidenceKey,
        BigDecimal capLuxMinutes,
        BigDecimal cumulativeLuxMinutes,
        String cumulativeDisplay,
        boolean doseBlocked,
        int version,
        String registeredBy,
        List<DoseReturnView> returns) {
}
