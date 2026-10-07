package com.example.starter.batch;

/**
 * 合成序贯检验在某次样本结果提交后的结论：
 * CONTINUE 未达阈值可继续抽样；PASS 似然比 &gt;= 9 序贯通过；
 * FAIL 似然比 &lt;= 1/9 序贯不通过（批次同时进入 REJECTED）；
 * INCONCLUSIVE 满 10 次仍未达阈值，不能批准放行。
 * PASS/FAIL/INCONCLUSIVE 均为终止结论，冻结后不得再提交样本。
 */
public enum SequentialConclusion {
    CONTINUE,
    PASS,
    FAIL,
    INCONCLUSIVE
}
