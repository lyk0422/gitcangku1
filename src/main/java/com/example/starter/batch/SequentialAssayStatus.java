package com.example.starter.batch;

/**
 * 合成序贯检验结论状态。
 * NOT_STARTED 尚无结果（似然比 1）；CONTINUE 未达阈值且未满 10 次，允许下一结果；
 * PASS 比值 &gt;= 9；FAIL 比值 &lt;= 1/9（同时使批次 REJECTED）；
 * INCONCLUSIVE 满 10 次仍未达阈值。PASS/FAIL/INCONCLUSIVE 均为终止态，
 * 终止后冻结完整前缀、精确分数与结论，不再接受新结果。
 */
public enum SequentialAssayStatus {
    NOT_STARTED,
    CONTINUE,
    PASS,
    FAIL,
    INCONCLUSIVE
}
