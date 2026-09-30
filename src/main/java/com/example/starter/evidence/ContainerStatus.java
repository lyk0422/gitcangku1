package com.example.starter.evidence;

/**
 * 容器状态。
 * ACTIVE 正常（封条完好，可放入证物与接受巡检）；
 * QUARANTINED 封条异常经两名保管人确认后隔离待复核（期间箱内证物禁止借出、归还与交接），
 * 复核放行后恢复 ACTIVE。
 */
public enum ContainerStatus {
    ACTIVE,
    QUARANTINED
}
