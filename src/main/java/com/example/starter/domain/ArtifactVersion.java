package com.example.starter.domain;

import java.util.List;

/**
 * 单个制品版本的不可变快照（含其声明的依赖、登记摘要与全部见证）。
 *
 * @param withdrawn    true 表示该版本已撤回
 * @param checksum     已登记的内容校验摘要；未登记为 null
 * @param attestations 全部见证记录（含已撤销），按角色升序
 */
public record ArtifactVersion(
        long id,
        String name,
        int version,
        boolean withdrawn,
        List<DependencyRange> dependencies,
        String checksum,
        List<Attestation> attestations) {

    /** 无摘要、无见证的兼容构造器（解析器单元测试使用）。 */
    public ArtifactVersion(long id, String name, int version, boolean withdrawn,
                           List<DependencyRange> dependencies) {
        this(id, name, version, withdrawn, dependencies, null, List.of());
    }
}
