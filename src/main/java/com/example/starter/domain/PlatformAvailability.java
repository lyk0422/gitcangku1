package com.example.starter.domain;

import java.util.Map;
import java.util.Set;

/**
 * 制品版本在各平台上的可用性快照。
 *
 * <p>某制品版本没有任何平台行时，视为在所有平台可用（向后兼容）；
 * 存在平台行时，仅在列出的平台可用。
 */
public record PlatformAvailability(Map<Long, Set<String>> platformsByArtifactId) {

    public PlatformAvailability {
        platformsByArtifactId = platformsByArtifactId == null
                ? Map.of() : Map.copyOf(platformsByArtifactId);
    }

    /** 判断制品版本在目标平台是否可用。 */
    public boolean available(ArtifactVersion artifact, String platform) {
        Set<String> platforms = platformsByArtifactId.get(artifact.id());
        return platforms == null || platforms.isEmpty() || platforms.contains(platform);
    }
}
