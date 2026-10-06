package com.example.starter.evidence.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 剂量归还视图：一次封条完好归还冻结的当次剂量、归还后累计剂量与全部剂量段。
 *
 * @param loanKey              本次归还对应的借出业务键
 * @param doseLuxMinutes       当次归还剂量（勒克斯分钟），各段剂量之和，不舍入
 * @param doseDisplay          当次剂量显示值，HALF_UP 保留4位小数，仅用于展示不参与判定
 * @param cumulativeLuxMinutes 归还后累计剂量（勒克斯分钟），历史冻结
 * @param cumulativeDisplay    归还后累计剂量显示值，HALF_UP 保留4位小数，仅用于展示不参与判定
 * @param segments             本次归还的全部剂量段（按时间顺序）
 * @param recordedAt           记录创建时间（Asia/Shanghai）
 */
public record DoseReturnView(
        String loanKey,
        BigDecimal doseLuxMinutes,
        String doseDisplay,
        BigDecimal cumulativeLuxMinutes,
        String cumulativeDisplay,
        List<DoseSegmentView> segments,
        LocalDateTime recordedAt) {
}
