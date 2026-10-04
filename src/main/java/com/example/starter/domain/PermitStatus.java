package com.example.starter.domain;

/** 运动间隔许可状态：创建后有效（ACTIVE）或被撤销（REVOKED）。 */
public enum PermitStatus {
    /** 有效许可，在整个有效区间内占用空域并参与间隔审查。 */
    ACTIVE,
    /**
     * 已撤销许可：撤销时刻起释放未来占用，撤销前的历史轨迹保留且仍计入占用；
     * 记录不可变保留，不再接受新的有效区间。
     */
    REVOKED
}