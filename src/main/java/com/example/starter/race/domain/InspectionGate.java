package com.example.starter.race.domain;

/**
 * 强制检录赛事的起跑门禁纯逻辑：依据“最近一条检录记录”与当前时刻判定是否放行，
 * 不涉及数据库与事务。
 *
 * <p>规则（按顺序判定，命中即返回）：
 * <ul>
 *   <li>赛事未启用强制检录（mandatory=false）时一律放行；</li>
 *   <li>选手没有任何检录记录：阻断，原因 NO_INSPECTION；</li>
 *   <li>最近一条为 FAIL：阻断，原因 LATEST_FAIL（FAIL 立即阻断起跑）；</li>
 *   <li>最近一条为 PASS 但当前时刻已晚于 valid_until（含端点视为有效）：阻断，原因 PASS_EXPIRED；</li>
 *   <li>其余情况（未过期 PASS）：放行。</li>
 * </ul>
 */
public final class InspectionGate {

    private InspectionGate() {
    }

    /** 门禁判定结果。 */
    public enum Verdict {
        /** 放行。 */
        ALLOWED,
        /** 阻断：选手不存在任何检录记录。 */
        NO_INSPECTION,
        /** 阻断：最近一条检录结果为 FAIL。 */
        LATEST_FAIL,
        /** 阻断：最近一条检录 PASS 已过有效期。 */
        PASS_EXPIRED
    }

    /** 检录记录视图（最近一条）。 */
    public interface InspectionView {
        InspectionResult result();

        /** PASS 有效期截止时刻（Unix毫秒，含端点）；FAIL 可为 null。 */
        Long validUntil();
    }

    /**
     * 判定是否允许起跑/接受首个分段计时。
     *
     * @param mandatory 赛事是否强制检录
     * @param latest    该选手最近一条检录记录；null 表示从未检录
     * @param nowMillis 当前时刻（Unix毫秒，由可注入时钟提供）
     * @return 判定结果；非 ALLOWED 时不允许写入计时/起跑
     */
    public static Verdict evaluate(boolean mandatory, InspectionView latest, long nowMillis) {
        if (!mandatory) {
            return Verdict.ALLOWED;
        }
        if (latest == null) {
            return Verdict.NO_INSPECTION;
        }
        if (latest.result() == InspectionResult.FAIL) {
            return Verdict.LATEST_FAIL;
        }
        Long validUntil = latest.validUntil();
        if (validUntil == null || nowMillis > validUntil) {
            return Verdict.PASS_EXPIRED;
        }
        return Verdict.ALLOWED;
    }

    /** 阻断原因的中文描述，用于 422 响应体。 */
    public static String reasonOf(Verdict verdict) {
        return switch (verdict) {
            case ALLOWED -> "允许";
            case NO_INSPECTION -> "选手不存在器材检录记录";
            case LATEST_FAIL -> "最近一次器材检录结果为FAIL，阻断起跑";
            case PASS_EXPIRED -> "器材检录PASS已过期";
        };
    }
}
