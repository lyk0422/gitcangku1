package com.example.starter.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 签名阈值验证器：在调用方选定的单一策略快照上，统计某制品版本的有效可信签名。
 *
 * <p>一份签名计入阈值当且仅当：
 * <ol>
 *   <li>keyId 属于当前策略可信清单；</li>
 *   <li>该钥匙当前未撤销；</li>
 *   <li>签名 digest 与制品内容摘要逐字符相等。</li>
 * </ol>
 * 同一钥匙对同一制品仅一份签名由数据库唯一约束保证；计入集合按 keyId 升序去重，
 * 数量不少于阈值 m 即通过。可信且未撤销钥匙的签名摘要不符属于硬失败（防止以追加坏签名规避校验）。
 * 本类为纯函数，不访问数据库，便于对失败分支做单元测试。
 */
public final class SignatureVerifier {

    /** 验证失败原因。 */
    public enum FailureReason {
        /** 存在可信未撤销钥匙提供的签名，但其 digest 与制品内容摘要不符。 */
        DIGEST_MISMATCH,
        /** 有效可信签名数量不足阈值 m。 */
        INSUFFICIENT_SIGNATURES
    }

    /**
     * 验证结果。
     *
     * @param success      是否达到阈值
     * @param signerKeyIds 实际计入阈值的 keyId 升序去重集合
     * @param reason       失败原因；成功时为 null
     */
    public record Result(boolean success, List<String> signerKeyIds, FailureReason reason) {

        public static Result ok(List<String> signerKeyIds) {
            return new Result(true, List.copyOf(signerKeyIds), null);
        }

        public static Result fail(List<String> signerKeyIds, FailureReason reason) {
            return new Result(false, List.copyOf(signerKeyIds), reason);
        }
    }

    private SignatureVerifier() {
    }

    /**
     * 对单个制品节点执行阈值验证。
     *
     * @param policy         当前生效策略（由调用方在事务快照内选定）
     * @param contentDigest  节点制品内容摘要
     * @param signatures     该制品版本的全部追加签名（含历史/不可信/已撤销钥匙的签名）
     * @param revokedKeyIds  当前已撤销钥匙集合
     */
    public static Result verify(SigningPolicy policy,
                                String contentDigest,
                                List<ArtifactSignature> signatures,
                                Set<String> revokedKeyIds) {
        Set<String> trusted = new HashSet<>(policy.keyIds());
        TreeSet<String> matched = new TreeSet<>();
        boolean digestMismatch = false;

        for (ArtifactSignature signature : signatures) {
            if (!trusted.contains(signature.keyId())) {
                continue;
            }
            if (revokedKeyIds.contains(signature.keyId())) {
                // 撤销钥匙：历史签名保留但不计入新锁定。
                continue;
            }
            if (!messageDigestEqual(contentDigest, signature.digest())) {
                digestMismatch = true;
                continue;
            }
            matched.add(signature.keyId());
        }

        List<String> signerKeyIds = new ArrayList<>(matched);
        if (digestMismatch) {
            return Result.fail(signerKeyIds, FailureReason.DIGEST_MISMATCH);
        }
        if (matched.size() < policy.threshold()) {
            return Result.fail(signerKeyIds, FailureReason.INSUFFICIENT_SIGNATURES);
        }
        return Result.ok(signerKeyIds);
    }

    private static boolean messageDigestEqual(String expected, String actual) {
        return expected != null && expected.equals(actual);
    }
}
