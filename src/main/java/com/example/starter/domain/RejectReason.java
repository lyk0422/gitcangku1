package com.example.starter.domain;

/**
 * 拒绝或挂起原因代码，供调用方区分失败分支。
 */
public final class RejectReason {

    /** 申请区间内潮高区间存在缺口，无法完成校核。 */
    public static final String TIDE_GAP = "TIDE_GAP";

    /** 泊位静态水深加最低潮高后仍小于吃水加安全余量。 */
    public static final String DEPTH_INSUFFICIENT = "DEPTH_INSUFFICIENT";

    /** 同一泊位已有已确认靠泊区间与申请区间重叠。 */
    public static final String BERTH_CONFLICT = "BERTH_CONFLICT";

    /** 人工复核驳回。 */
    public static final String REVIEW_REJECTED = "REVIEW_REJECTED";

    private RejectReason() {
    }
}
