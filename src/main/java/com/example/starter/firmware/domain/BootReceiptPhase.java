package com.example.starter.firmware.domain;

/**
 * 双分区回执阶段：TRIAL_BOOT 试启动回执（进入 TRIAL，不切换版本）；
 * HEALTH_CONFIRM 健康确认回执（60 秒窗口内才 COMMITTED 并正式切换）。
 * 固件下载成功不代表启动健康，SUCCESS 回执必须携带阶段以区分试启动与正式切换。
 */
public enum BootReceiptPhase {
    TRIAL_BOOT,
    HEALTH_CONFIRM
}
