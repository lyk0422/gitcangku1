package com.example.starter.race.domain;

/**
 * 分段计时被排除出排名的原因：
 * MEDICAL_HOLD-提交时该选手存在生效中的医疗暂停，记录保留但不参与排名。
 */
public enum ExclusionReason {
    MEDICAL_HOLD
}
