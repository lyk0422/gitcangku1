package com.example.starter.disclosure.dto;

import java.util.List;

/**
 * 披露包完整内容：包视图 + 按规范化顺序排列的逐件冻结快照。
 *
 * @param pkg   包视图
 * @param items 逐件冻结快照
 */
public record PackageContentView(
        PackageView pkg,
        List<PackageItemView> items) {
}
