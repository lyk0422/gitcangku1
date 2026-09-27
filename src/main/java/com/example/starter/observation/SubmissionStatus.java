package com.example.starter.observation;

/**
 * 观测提交的处理状态。
 */
public enum SubmissionStatus {
    /**
     * 待合并：原始记录已保存，尚未进入冲突簇合并。
     */
    PENDING,
    /**
     * 待人工裁决：所属冲突簇只含受影响观测，自动合并判定为 REVIEW，等待人工裁决。
     */
    REVIEW,
    /**
     * 已并入观测版本：无论作为胜出内容还是被覆盖的簇成员，均不再参与后续合并。
     */
    CONSUMED
}
