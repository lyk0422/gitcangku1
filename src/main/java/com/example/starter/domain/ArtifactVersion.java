package com.example.starter.domain;

import java.util.List;

/**
 * 单个制品版本的不可变快照（含其声明的依赖）。
 *
 * @param contentDigest 制品内容摘要（SHA-256 十六进制小写），签名 digest 必须与之相等
 * @param withdrawn     true 表示该版本已撤回
 */
public record ArtifactVersion(
        long id,
        String name,
        int version,
        boolean withdrawn,
        String contentDigest,
        List<DependencyRange> dependencies) {
}
