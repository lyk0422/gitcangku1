package com.example.starter.api.dto;

import java.util.List;

/**
 * 镜像故障切换查询结果：返回锁文件中该名称当前锁定版本登记的镜像里，
 * 按优先级从高到低第一个当前可用的镜像。
 */
public record MirrorFailoverResponse(
        long lockFileId,
        String name,
        int version,
        String mirrorId,
        int priority,
        List<MirrorView> registeredMirrors) {
}
