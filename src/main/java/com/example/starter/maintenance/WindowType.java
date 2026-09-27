package com.example.starter.maintenance;

/**
 * 维护窗口类型：初始登记（REGISTER）或对已封存窗口的事后更正（CORRECTION）。
 */
public enum WindowType {
    /**
     * 站点维护窗口的初始登记。
     */
    REGISTER,
    /**
     * 对已被合并快照封存、不可修改的历史窗口追加的更正窗口。
     */
    CORRECTION
}
