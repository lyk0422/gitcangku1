package com.example.starter.domain;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 锁图签名阈值校验：对闭包中每个节点，依据当前生效策略统计
 * “未撤销、被本策略信任、摘要与制品冻结摘要一致”的去重 keyId 集合。
 *
 * <p>候选签名（钥匙属于本策略且未撤销）的摘要必须与制品内容摘要一致，
 * 任一候选摘要不符即判定该节点验证失败；不属于本策略或来自已撤销钥匙的
 * 历史签名不参与计数，也不导致失败。
 */
public final class SignatureGate {

    /** 节点校验结果。 */
    public sealed interface Result {
        /** 节点名称。 */
        String nodeName();

        /** 节点版本。 */
        int nodeVersion();
    }

    /** 校验通过：携带实际计入阈值的 keyId（升序、去重）。 */
    public record Passed(String nodeName, int nodeVersion, List<String> countedKeyIds)
            implements Result {
    }

    /** 校验失败原因。 */
    public enum FailureReason {
        /** 候选签名中存在摘要与制品内容摘要不符的签名。 */
        DIGEST_MISMATCH,
        /** 合格签名的去重钥匙数不足阈值 m。 */
        INSUFFICIENT_SIGNATURES
    }

    /** 校验失败：携带原因、合格数与阈值供错误响应使用。 */
    public record Failed(String nodeName, int nodeVersion, FailureReason reason,
                         int qualifiedCount, int threshold) implements Result {
    }

    private SignatureGate() {
    }

    /**
     * 评估单个节点。
     *
     * @param policy      当前生效策略（非空）
     * @param revokedKeys 全部已撤销钥匙集合（事务快照内读取）
     * @param artifact    被锁定的精确制品版本（含冻结内容摘要）
     * @param signatures  该制品版本的全部历史签名
     * @return Passed（含计入阈值的升序 keyId）或 Failed
     */
    public static Result evaluate(SignaturePolicy policy,
                                  Set<String> revokedKeys,
                                  ArtifactVersion artifact,
                                  List<ArtifactSignature> signatures) {
        Set<String> trusted = new TreeSet<>(policy.keyIds());
        TreeSet<String> counted = new TreeSet<>();
        boolean digestMismatch = false;

        for (ArtifactSignature signature : signatures) {
            String keyId = signature.keyId();
            if (!trusted.contains(keyId) || revokedKeys.contains(keyId)) {
                continue;
            }
            if (!artifact.contentDigest().equals(signature.digest())) {
                digestMismatch = true;
                continue;
            }
            counted.add(keyId);
        }

        if (digestMismatch) {
            return new Failed(artifact.name(), artifact.version(), FailureReason.DIGEST_MISMATCH,
                    counted.size(), policy.thresholdM());
        }
        if (counted.size() < policy.thresholdM()) {
            return new Failed(artifact.name(), artifact.version(),
                    FailureReason.INSUFFICIENT_SIGNATURES, counted.size(), policy.thresholdM());
        }
        return new Passed(artifact.name(), artifact.version(), List.copyOf(counted));
    }
}
