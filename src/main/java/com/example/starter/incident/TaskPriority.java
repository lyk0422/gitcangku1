package com.example.starter.incident;

/**
 * 处置任务优先级：NORMAL 普通 / HIGH 高。
 * 仅 HIGH 任务受外部机构回执门禁约束：所属事件当前配置版本的全部必需机构确认前，
 * 或事件处于 EXTERNAL_BLOCKED 时，HIGH 任务不得完成（422）。
 */
public enum TaskPriority {

    /** 普通任务，不受外部机构门禁约束。 */
    NORMAL,

    /** 高优先级任务，受外部机构回执门禁约束。 */
    HIGH;
}
