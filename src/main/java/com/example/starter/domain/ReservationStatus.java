package com.example.starter.domain;

/**
 * 备降机场预留状态。预留占用容量的生命周期状态，替换关系一旦写入永不改变。
 */
public enum ReservationStatus {
    /** 已预留：未起飞且未被挤出，当前占用机场窗口容量。 */
    RESERVED,
    /** 已起飞：仍占用窗口容量直至窗口结束，但不能再被任何申请挤出。 */
    DEPARTED,
    /** 已被挤出：被更高优先级申请整体挤出，不再占用容量；记录与替换链不可变保留。 */
    DISPLACED
}
