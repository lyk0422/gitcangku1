package com.example.starter.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 许可证告知门禁评估器：对一张锁定图的最终依赖闭包执行策略命中与告知合规校验。
 *
 * <p>纯逻辑、无副作用。策略与绑定按提交顺序裁决（同目标多条时 ID 最大者胜）。
 * 校验输出保持稳定排序：命中与缺失均按制品名称升序，路径为 BFS 最短路径。
 */
public final class LicenseGate {

    /** NOTICE_REQUIRED 动作。 */
    public static final String ACTION_NOTICE_REQUIRED = "NOTICE_REQUIRED";
    /** ALLOWED 动作。 */
    public static final String ACTION_ALLOWED = "ALLOWED";

    /** 文本已批准。 */
    public static final String STATUS_APPROVED = "APPROVED";
    /** 文本为草稿。 */
    public static final String STATUS_DRAFT = "DRAFT";
    /** 文本已撤销。 */
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";

    private LicenseGate() {
    }

    /** 策略输入：id 即提交顺序，越大越新。 */
    public record GatePolicy(long id, String scopeType, Long lockFileId,
                             String artifactName, Integer artifactVersion,
                             String licenseId, String action) {
    }

    /** 绑定输入：id 即提交顺序，越大越新。 */
    public record GateBinding(long id, String scopeType, Long lockFileId,
                              String artifactName, Integer artifactVersion,
                              String licenseId, String noticeKey, int noticeVersion) {
    }

    /** 告知文本输入。 */
    public record GateNotice(String noticeKey, int version, String licenseId,
                             String status, Set<String> regions) {
    }

    /** 闭包内一个制品的图位置信息。 */
    public record GateArtifact(String name, int version, boolean direct, List<String> path) {
    }

    /** 一条命中结果（含 NOTICE_REQUIRED 与 ALLOWED）。 */
    public record Hit(String name, int version, boolean direct, List<String> path,
                      String scopeType, String licenseId, String action,
                      String noticeKey, Integer noticeVersion, List<String> noticeRegions) {
    }

    /** 一条告知缺失/不合规结果。 */
    public record Missing(String reason, String name, int version, boolean direct,
                          List<String> path, String licenseId, String detail) {
    }

    /** 评估结果。 */
    public record GateResult(List<Hit> hits, List<Missing> missing) {

        public boolean compliant() {
            return missing.isEmpty();
        }
    }

    /** 缺失原因码。 */
    public static final String REASON_NOTICE_MISSING = "NOTICE_MISSING";
    /** 缺失原因码：文本未批准（草稿）。 */
    public static final String REASON_TEXT_NOT_APPROVED = "TEXT_NOT_APPROVED";
    /** 缺失原因码：文本已撤销。 */
    public static final String REASON_TEXT_WITHDRAWN = "TEXT_WITHDRAWN";
    /** 缺失原因码：地区不覆盖。 */
    public static final String REASON_REGION_NOT_COVERED = "REGION_NOT_COVERED";

    /**
     * 执行门禁评估。
     *
     * @param lockFileId 被评估锁定图 ID（用于 LOCK 作用域匹配）
     * @param artifacts  闭包制品（含直接标记与命中路径），按名称升序
     * @param policies   全部策略（含其他锁的，内部按作用域过滤）
     * @param bindings   全部绑定
     * @param notices    告知文本索引：noticeKey + "#" + version -> 文本
     * @param requiredRegions 发布要求覆盖的地区集合；空集合表示跳过地区覆盖检查
     */
    public static GateResult evaluate(long lockFileId,
                                      List<GateArtifact> artifacts,
                                      List<GatePolicy> policies,
                                      List<GateBinding> bindings,
                                      Map<String, GateNotice> notices,
                                      Set<String> requiredRegions) {
        List<Hit> hits = new ArrayList<>();
        List<Missing> missing = new ArrayList<>();

        for (GateArtifact artifact : artifacts) {
            GatePolicy policy = selectPolicy(lockFileId, artifact, policies);
            if (policy == null) {
                continue;
            }
            GateBinding binding = selectBinding(lockFileId, artifact, policy.licenseId(), bindings);
            GateNotice notice = binding == null ? null
                    : notices.get(binding.noticeKey() + "#" + binding.noticeVersion());

            hits.add(new Hit(artifact.name(), artifact.version(), artifact.direct(),
                    List.copyOf(artifact.path()), policy.scopeType(), policy.licenseId(),
                    policy.action(), binding == null ? null : binding.noticeKey(),
                    binding == null ? null : binding.noticeVersion(),
                    notice == null ? List.of() : List.copyOf(notice.regions())));

            if (ACTION_NOTICE_REQUIRED.equals(policy.action())) {
                validateNotice(artifact, policy, binding, notice, requiredRegions, missing);
            }
        }
        return new GateResult(List.copyOf(hits), List.copyOf(missing));
    }

    /**
     * 选择作用于该制品的最新策略：LOCK 作用域优先于 COORDINATE；
     * 同作用域内按提交 ID 最大者胜。
     */
    private static GatePolicy selectPolicy(long lockFileId, GateArtifact artifact,
                                           List<GatePolicy> policies) {
        GatePolicy lockScoped = null;
        GatePolicy coordinateScoped = null;
        for (GatePolicy policy : policies) {
            if ("LOCK".equals(policy.scopeType())) {
                if (policy.lockFileId() != null && policy.lockFileId() == lockFileId
                        && policy.artifactName().equals(artifact.name())
                        && (lockScoped == null || policy.id() > lockScoped.id())) {
                    lockScoped = policy;
                }
            } else if ("COORDINATE".equals(policy.scopeType())) {
                if (policy.artifactName().equals(artifact.name())
                        && (policy.artifactVersion() == null
                        || policy.artifactVersion() == artifact.version())
                        && (coordinateScoped == null || policy.id() > coordinateScoped.id())) {
                    coordinateScoped = policy;
                }
            }
        }
        return lockScoped != null ? lockScoped : coordinateScoped;
    }

    /**
     * 选择该制品针对命中许可证的最新绑定：LOCK 作用域优先于 COORDINATE。
     */
    private static GateBinding selectBinding(long lockFileId, GateArtifact artifact,
                                             String licenseId, List<GateBinding> bindings) {
        GateBinding lockScoped = null;
        GateBinding coordinateScoped = null;
        for (GateBinding binding : bindings) {
            if (!binding.licenseId().equals(licenseId)) {
                continue;
            }
            if ("LOCK".equals(binding.scopeType())) {
                if (binding.lockFileId() != null && binding.lockFileId() == lockFileId
                        && binding.artifactName().equals(artifact.name())
                        && (lockScoped == null || binding.id() > lockScoped.id())) {
                    lockScoped = binding;
                }
            } else if ("COORDINATE".equals(binding.scopeType())) {
                if (binding.artifactName().equals(artifact.name())
                        && (binding.artifactVersion() == null
                        || binding.artifactVersion() == artifact.version())
                        && (coordinateScoped == null || binding.id() > coordinateScoped.id())) {
                    coordinateScoped = binding;
                }
            }
        }
        return lockScoped != null ? lockScoped : coordinateScoped;
    }

    private static void validateNotice(GateArtifact artifact, GatePolicy policy,
                                       GateBinding binding, GateNotice notice,
                                       Set<String> requiredRegions, List<Missing> missing) {
        if (binding == null || notice == null) {
            missing.add(new Missing(REASON_NOTICE_MISSING, artifact.name(), artifact.version(),
                    artifact.direct(), List.copyOf(artifact.path()), policy.licenseId(),
                    binding == null ? "未绑定已批准的告知文本版本" : "绑定的告知文本版本不存在"));
            return;
        }
        if (!notice.licenseId().equals(policy.licenseId())) {
            missing.add(new Missing(REASON_NOTICE_MISSING, artifact.name(), artifact.version(),
                    artifact.direct(), List.copyOf(artifact.path()), policy.licenseId(),
                    "告知文本许可证 " + notice.licenseId() + " 与命中策略许可证 "
                            + policy.licenseId() + " 不一致"));
            return;
        }
        if (STATUS_DRAFT.equals(notice.status())) {
            missing.add(new Missing(REASON_TEXT_NOT_APPROVED, artifact.name(), artifact.version(),
                    artifact.direct(), List.copyOf(artifact.path()), policy.licenseId(),
                    "告知文本 " + notice.noticeKey() + ":" + notice.version() + " 尚未批准"));
            return;
        }
        if (STATUS_WITHDRAWN.equals(notice.status())) {
            missing.add(new Missing(REASON_TEXT_WITHDRAWN, artifact.name(), artifact.version(),
                    artifact.direct(), List.copyOf(artifact.path()), policy.licenseId(),
                    "告知文本 " + notice.noticeKey() + ":" + notice.version() + " 已撤销"));
            return;
        }
        if (!requiredRegions.isEmpty() && !notice.regions().containsAll(requiredRegions)) {
            LinkedHashSet<String> uncovered = new LinkedHashSet<>(requiredRegions);
            uncovered.removeAll(notice.regions());
            missing.add(new Missing(REASON_REGION_NOT_COVERED, artifact.name(), artifact.version(),
                    artifact.direct(), List.copyOf(artifact.path()), policy.licenseId(),
                    "告知文本地区 " + notice.regions() + " 未覆盖目标地区 " + uncovered));
        }
    }
}
