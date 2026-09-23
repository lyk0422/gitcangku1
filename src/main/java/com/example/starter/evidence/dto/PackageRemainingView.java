package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 组合包剩余未归还集合视图。只读，按包内稳定排序返回。
 *
 * @param packageKey 组合包业务键
 * @param remaining  剩余未归还证物业务键（稳定排序）
 */
public record PackageRemainingView(
        String packageKey,
        List<String> remaining) {
}
