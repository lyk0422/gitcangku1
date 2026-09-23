package com.example.starter.incident;

/**
 * 依赖图边的变更动作 / 边来源。
 * ADD 新增一条有向依赖边，DELETE 删除一条已有边；
 * TASK 表示边由处置任务创建引入，PROPOSAL 表示边由变更提案激活引入。
 */
public enum EdgeOp {

    /** 新增有向边（from 事件依赖 to 事件）。 */
    ADD,

    /** 删除有向边。 */
    DELETE;

    /** 任务创建引入的边。 */
    public static final String SOURCE_TASK = "TASK";

    /** 提案激活引入的边。 */
    public static final String SOURCE_PROPOSAL = "PROPOSAL";
}
