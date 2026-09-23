package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 组合包借出撤销结果。撤销原子执行：全部证物恢复 SEALED，组合包及明细整体移除，
 * 不形成任何部分借出记录。
 *
 * @param packageKey            已撤销的组合包业务键
 * @param cancelled             是否已撤销（恒为 true）
 * @param restoredEvidenceKeys  恢复为 SEALED 的证物业务键（按包内稳定排序）
 */
public record PackageCancelView(
        String packageKey,
        boolean cancelled,
        List<String> restoredEvidenceKeys) {
}
