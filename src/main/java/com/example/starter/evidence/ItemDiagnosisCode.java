package com.example.starter.evidence;

/**
 * 单件证物按截至 UTC 时刻重放后的诊断码，建包失败时逐项返回、原因可区分：
 * OK 通过；其余编码分别对应不属于案件、截至时刻封签异常、待核验、借出未归还与保管链缺口。
 */
public enum ItemDiagnosisCode {

    /** 截至时刻重放通过：证物在案、封签正常、非待核验、无未归还借出、保管链连续。 */
    OK,

    /** 证物业务键不存在。 */
    EVIDENCE_NOT_FOUND,

    /** 证物存在但不属于请求案件。 */
    NOT_IN_CASE,

    /** 保管链缺口：截至时刻证物尚未入库，或已闭环交接的发起保管人与重放保管人不一致。 */
    CHAIN_GAP,

    /** 截至时刻证物处于待核验状态（交接已发起但尚未接受或取消）。 */
    PENDING_TRANSFER_AT_AS_OF,

    /** 截至时刻证物处于借出未归还状态（借出发生在截至时刻前，归还为空或晚于截至时刻）。 */
    ACTIVE_LOAN_AT_AS_OF,

    /** 截至时刻封签异常（截至时刻前已存在失败封条核验）。 */
    SEAL_BROKEN_AT_AS_OF
}
