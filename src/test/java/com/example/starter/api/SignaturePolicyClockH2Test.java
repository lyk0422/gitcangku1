package com.example.starter.api;

import com.example.starter.api.dto.AddSignatureRequest;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PublishPolicyRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用固定时钟的策略生效边界测试：生效时刻相等即生效、未到时刻不选策略。
 * 独立上下文提供固定 Clock Bean，保证时间语义确定性。
 */
@SpringBootTest
class SignaturePolicyClockH2Test {

    private static final Instant FIXED = Instant.parse("2026-06-01T12:00:00Z");

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }
    }

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_signature");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("DELETE FROM policy_key");
        jdbcTemplate.update("DELETE FROM signature_policy");
        jdbcTemplate.update("DELETE FROM trusted_key");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    @Test
    void policyEffectiveExactlyAtClockInstantIsSelected() {
        service.registerArtifact(rid(), new RegisterArtifactRequest("app", 1, List.of()));
        String digest = jdbcTemplate.queryForObject(
                "SELECT content_digest FROM artifact WHERE name = 'app' AND version = 1",
                String.class);
        // effectiveAt 恰好等于固定时钟时刻：effective_at <= now 包含相等边界。
        service.publishPolicy(rid(), new PublishPolicyRequest(
                1L, List.of("k1"), 1, FIXED));
        service.addSignature(rid(), "app", 1, new AddSignatureRequest("k1", digest));

        LockFileResponse lock = service.createLock(rid(),
                new LockRequest("app", 1, currentVersion()));
        assertThat(lock.entries().getFirst().policyVersion()).isEqualTo(1L);
        assertThat(lock.entries().getFirst().signatureKeyIds()).containsExactly("k1");
        assertThat(lock.createdAt()).isEqualTo(FIXED);
    }

    @Test
    void policyEffectiveInFutureIsNotSelected() {
        service.registerArtifact(rid(), new RegisterArtifactRequest("app", 1, List.of()));
        // 生效时刻晚固定时钟 1 秒：当前无生效策略，即使无签名也可锁定。
        service.publishPolicy(rid(), new PublishPolicyRequest(
                1L, List.of("k1"), 1, FIXED.plusSeconds(1)));

        LockFileResponse lock = service.createLock(rid(),
                new LockRequest("app", 1, currentVersion()));
        assertThat(lock.entries().getFirst().policyVersion()).isNull();
        assertThat(lock.entries().getFirst().signatureKeyIds()).isEmpty();
    }

    private long currentVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }
}
