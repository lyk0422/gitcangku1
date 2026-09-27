package com.example.starter.firmware.api;

/**
 * 设备拉取响应：task 为命中或已存在的任务；block 为模块兼容阻断（MODULE_BLOCKED），
 * 此时不创建任务且不计入失败率；两者均为 null 表示无匹配投放。
 */
public record PullResponse(TaskView task, ModuleBlockView block) {

    public static PullResponse ofTask(TaskView task) {
        return new PullResponse(task, null);
    }

    public static PullResponse ofBlock(ModuleBlockView block) {
        return new PullResponse(null, block);
    }

    public static PullResponse empty() {
        return new PullResponse(null, null);
    }
}
