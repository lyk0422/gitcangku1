package com.example.starter.incident;

/**
 * 处置任务优先级。仅 HIGH 任务受外部机构回执门禁约束（全部必需机构确认前不得完成）；
 * NORMAL 任务不受该门禁影响。优先级创建后不可修改。
 */
public enum TaskPriority {

    /** 高优先级：受外部机构确认门禁约束。 */
    HIGH,

    /** 普通优先级（默认）：不受外部机构确认门禁约束。 */
    NORMAL;

    /**
     * 解析优先级参数，非法值抛 IllegalArgumentException 由调用方转 400。
     */
    public static TaskPriority parse(String value) {
        if (value == null) {
            return NORMAL;
        }
        return TaskPriority.valueOf(value.strip().toUpperCase());
    }
}
