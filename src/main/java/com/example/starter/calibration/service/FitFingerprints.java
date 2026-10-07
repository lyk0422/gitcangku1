package com.example.starter.calibration.service;

import java.math.BigDecimal;

import com.example.starter.calibration.api.dto.FitPoint;
import com.example.starter.calibration.api.dto.FitSubmitRequest;

/**
 * 幂等参数指纹：结构化拼接操作、资源、全参数与期望版本。
 * 十进制按值规范化（去尾零，零归一为 "0"），文本原样保留。
 */
final class FitFingerprints {

    private FitFingerprints() {
    }

    /**
     * 提交拟合的指纹：仪器、区间、残差上限、提交人、期望版本与全部参考点（顺序敏感）。
     */
    static String ofSubmit(FitSubmitRequest req) {
        StringBuilder sb = new StringBuilder("FIT_SUBMIT");
        field(sb, "instrument", req.instrumentId(), false);
        field(sb, "from", req.validFrom(), false);
        field(sb, "to", req.validTo(), false);
        field(sb, "maxResidual", req.maxResidual(), true);
        field(sb, "submittedBy", req.submittedBy(), false);
        sb.append("|ev=").append(req.expectedVersion() == null ? "∅" : req.expectedVersion());
        sb.append("|points=");
        if (req.points() == null) {
            sb.append("∅");
        } else {
            for (FitPoint point : req.points()) {
                sb.append('(');
                sb.append(point == null ? "∅" : decimal(point.x()));
                sb.append(',');
                sb.append(point == null ? "∅" : decimal(point.y()));
                sb.append(')');
            }
        }
        return sb.toString();
    }

    /**
     * 批准拟合的指纹：拟合 ID、批准人与期望版本。
     */
    static String ofApprove(long fitId, String actor, Long expectedVersion) {
        return "FIT_APPROVE|fit=" + fitId
                + "|actor=" + (actor == null ? "∅" : actor)
                + "|ev=" + (expectedVersion == null ? "∅" : expectedVersion);
    }

    private static void field(StringBuilder sb, String name, String value, boolean numeric) {
        sb.append('|').append(name).append('=');
        sb.append(value == null ? "∅" : numeric ? decimal(value) : value);
    }

    /**
     * 十进制按值规范化：能解析则去尾零（零归一为 "0"），否则按原文保留。
     */
    private static String decimal(String value) {
        try {
            BigDecimal parsed = new BigDecimal(value.trim());
            if (parsed.signum() == 0) {
                return "0";
            }
            return parsed.stripTrailingZeros().toPlainString();
        } catch (RuntimeException ex) {
            return value;
        }
    }
}
