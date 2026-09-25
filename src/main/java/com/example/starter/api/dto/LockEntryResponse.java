package com.example.starter.api.dto;

import java.util.List;

/**
 * 锁文件中单个名称对应的精确版本。
 *
 * @param mirrors 锁定时固化的镜像清单快照：按优先级升序、仅含当时可用镜像；
 *                锁定后镜像可用性变更不回写；该版本未登记镜像或锁定时全部不可用则为空列表
 */
public record LockEntryResponse(String name, int version, List<LockMirrorView> mirrors) {

    public LockEntryResponse {
        if (mirrors == null) {
            mirrors = List.of();
        } else {
            mirrors = List.copyOf(mirrors);
        }
    }

    /** 无镜像场景的便捷构造器。 */
    public LockEntryResponse(String name, int version) {
        this(name, version, List.of());
    }
}
