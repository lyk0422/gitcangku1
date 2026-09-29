package com.example.starter.translation.archive;

import com.example.starter.translation.archive.ArchiveDtos.BatchArchiveRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 归档写操作幂等指纹工具。
 *
 * <p>指纹覆盖操作类型（含路径变量）、操作者、规范化对象集合与全部影响结果的字段。
 * 语种集合先 trim、小写、去重并字典序排序，再以 JSON 数组参与摘要——不使用任何分隔符拼接字符串，
 * 因此含逗号等字符的值不会造成不同集合指纹碰撞。</p>
 */
@Component
public class ArchiveFingerprint {

    private final ObjectMapper objectMapper;

    public ArchiveFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 单包创建指纹：操作类型 + 操作者 + 发布版本 + 规范化语种集合。 */
    public String single(String operation, String actor, int publishedVersion, List<String> languages) {
        return digest(operation, actor, canonicalSingle(publishedVersion, languages));
    }

    /** 批量创建指纹：操作类型 + 操作者 + 按发布版本排序并规范化的全部批量项。 */
    public String batch(String operation, String actor, BatchArchiveRequest request) {
        List<Map<String, Object>> canonical = request.items().stream()
                .map(item -> canonicalSingle(item.publishedVersion(), item.targetLanguages()))
                .sorted((a, b) -> Integer.compare((int) a.get("publishedVersion"), (int) b.get("publishedVersion")))
                .toList();
        return digest(operation, actor, canonical);
    }

    /** 撤回/撤销指纹：操作类型 + 操作者 + 路径版本 + 原因。 */
    public String action(String operation, String actor, int pathVersion, String reason) {
        return digest(operation, actor, pathVersion, reason);
    }

    /** 规范化语种集合：trim、小写、去重、字典序。 */
    public static List<String> normalizeLanguageSet(List<String> languages) {
        return languages.stream()
                .map(language -> language.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
    }

    private Map<String, Object> canonicalSingle(int publishedVersion, List<String> languages) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("publishedVersion", publishedVersion);
        canonical.put("targetLanguages", normalizeLanguageSet(languages));
        return canonical;
    }

    private String digest(String operation, Object... parts) {
        try {
            StringBuilder canonical = new StringBuilder(operation);
            for (Object part : parts) {
                canonical.append('\n').append(part instanceof String s ? s : objectMapper.writeValueAsString(part));
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("指纹序列化失败", e);
        }
    }
}
