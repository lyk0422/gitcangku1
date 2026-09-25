package com.example.starter.incident;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 租约资质覆盖校验：资源必须拥有租约快照中全部必需资质，
 * 且资质有效期严格覆盖任务计划完成时刻（valid_until 严格晚于 lease_end），
 * 在评估时刻（分配/开始/替换的事务时刻）资质须已生效且未撤销。
 */
@Component
public class CredentialCoverage {

    private final ResourceCredentialRepository credentials;

    public CredentialCoverage(ResourceCredentialRepository credentials) {
        this.credentials = credentials;
    }

    /**
     * 覆盖失败明细：missing 为缺失或已撤销的资质代码；expired 为未严格覆盖计划完成时刻
     * （到期/未生效）的资质代码。两者均按字典序返回。
     */
    public record Failure(List<String> missing, List<String> expired) {
        public boolean hasFailure() {
            return !missing.isEmpty() || !expired.isEmpty();
        }
    }

    /**
     * 评估租约资源对租约必需资质集合的覆盖情况。
     *
     * @param lease 租约（必需资质快照与计划完成时刻 leaseEnd）
     * @param at    评估 UTC 时刻（事务当前时刻）
     */
    public Failure evaluate(ResourceLease lease, Instant at) {
        java.util.TreeSet<String> missing = new java.util.TreeSet<>();
        java.util.TreeSet<String> expired = new java.util.TreeSet<>();
        for (String code : lease.requiredCredentials()) {
            var found = credentials.find(lease.resourceId(), code);
            if (found.isEmpty() || found.get().status() == CredentialStatus.REVOKED) {
                missing.add(code);
                continue;
            }
            ResourceCredential credential = found.get();
            boolean effectiveNow = credential.validFrom() == null
                    || !credential.validFrom().isAfter(at);
            boolean coversPlannedEnd = credential.validUntil().isAfter(lease.leaseEnd());
            if (!effectiveNow || !coversPlannedEnd) {
                expired.add(code);
            }
        }
        return new Failure(List.copyOf(missing), List.copyOf(expired));
    }
}
