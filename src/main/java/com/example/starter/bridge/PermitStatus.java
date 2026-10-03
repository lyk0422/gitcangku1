package com.example.starter.bridge;

/**
 * 许可状态机：APPROVED 已核准（未进桥，可撤销）；
 * ENTERED 已进桥（占用持续到实际出桥，不可撤销）；
 * EXITED 已出桥（终态）；CANCELLED 已撤销（终态）。
 */
public enum PermitStatus {
    /** 已核准，预计窗口被锁定，尚未进桥。 */
    APPROVED,
    /** 已进桥，实际占桥中，只能出桥。 */
    ENTERED,
    /** 已出桥，占用释放，终态。 */
    EXITED,
    /** 已撤销，预计窗口释放，终态。 */
    CANCELLED
}