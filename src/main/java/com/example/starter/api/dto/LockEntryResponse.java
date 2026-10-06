package com.example.starter.api.dto;

import java.util.List;

/**
 * 锁文件中单个名称对应的精确版本及其所在作用域。
 *
 * @param scopePath 作用域路径：创建该作用域链的父制品名称序列（结构化数组，
 *                  根作用域为空数组），不做分隔符拼接
 */
public record LockEntryResponse(String name, int version, List<String> scopePath) {

    /** 旧扁平锁条目：根作用域。 */
    public LockEntryResponse(String name, int version) {
        this(name, version, List.of());
    }
}
