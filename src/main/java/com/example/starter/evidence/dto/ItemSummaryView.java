package com.example.starter.evidence.dto;

/**
 * 单件固化生成摘要：建包时按截至 UTC 时刻重放得到的链版本、位置、封签、待核验与借还状态。
 * 全部数字均为“截至时刻之前（含等于）”的历史统计，之后新增链记录不改变该摘要。
 *
 * @param chainVersion              截至时刻链版本：交接+借出+核验记录总条数
 * @param transferCountUpToAsOf     截至时刻前交接记录条数
 * @param acceptedTransferCount     截至时刻前已接受交接条数
 * @param cancelledTransferCount    截至时刻前已取消交接条数
 * @param loanCountUpToAsOf         截至时刻前借出记录条数
 * @param returnedLoanCountUpToAsOf 截至时刻前已归还借出条数
 * @param inspectionCountUpToAsOf   截至时刻前封条核验记录条数
 * @param passedInspectionCount     截至时刻前核验通过条数
 * @param custodianAtAsOf           截至时刻保管人（位置）
 * @param sealIntactAtAsOf          截至时刻封签是否正常
 * @param pendingTransferAtAsOf     截至时刻是否处于待核验（成功包固定 false）
 * @param activeLoanAtAsOf          截至时刻是否借出未归还（成功包固定 false）
 * @param activeLoanBorrowerAtAsOf  截至时刻未归还借出的借用人；成功包固定 null
 */
public record ItemSummaryView(
        int chainVersion,
        int transferCountUpToAsOf,
        int acceptedTransferCount,
        int cancelledTransferCount,
        int loanCountUpToAsOf,
        int returnedLoanCountUpToAsOf,
        int inspectionCountUpToAsOf,
        int passedInspectionCount,
        String custodianAtAsOf,
        boolean sealIntactAtAsOf,
        boolean pendingTransferAtAsOf,
        boolean activeLoanAtAsOf,
        String activeLoanBorrowerAtAsOf) {
}
