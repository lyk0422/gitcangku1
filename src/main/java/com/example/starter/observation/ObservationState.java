package com.example.starter.observation;

/**
 * 按时刻（AS OF）视图与冻结快照中单条观测记录的状态。
 */
public enum ObservationState {

    /**
     * 目标时刻记录已存在且最后版本为正常版本，业务字段可取。
     */
    PRESENT,

    /**
     * 目标时刻记录的最后版本是删除墓碑（删除已在该时刻生效）；业务字段无意义。
     */
    DELETED,

    /**
     * 目标时刻记录尚未创建（无任何已提交版本）；不视为 404。
     */
    ABSENT
}
