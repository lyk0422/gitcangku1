package com.example.starter.api.dto;

import java.util.List;

/**
 * 锁文件中单个名称对应的精确版本及其固化的镜像清单。
 *
 * @param mirrors 锁定当时按优先级升序、过滤不可用后的镜像清单；无镜像时为空列表，永不为 null
 */
public record LockEntryResponse(String name, int version, List<MirrorView> mirrors) {
}
