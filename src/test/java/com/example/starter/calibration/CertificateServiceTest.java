package com.example.starter.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.calibration.domain.Certificate;
import com.example.starter.calibration.error.ApiException;
import com.example.starter.calibration.service.CertificateService;
import com.example.starter.calibration.support.InMemoryRepositories;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 证书服务测试：创建、区间重叠、相邻区间、撤销及并发创建。
 */
class CertificateServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private InMemoryRepositories repositories;
    private CertificateService service;

    @BeforeEach
    void setUp() {
        repositories = new InMemoryRepositories();
        service = new CertificateService(
                repositories.certificates(), Clock.fixed(T0, ZoneOffset.UTC));
    }

    private Certificate create(String instrument, String from, String to) {
        return service.create(
                instrument,
                Instant.parse(from),
                Instant.parse(to),
                new BigDecimal("1.5"),
                new BigDecimal("0.25"));
    }

    @Test
    void createCertificateSucceeds() {
        Certificate certificate = create("inst-1", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z");
        assertTrue(certificate.id() > 0);
        assertFalse(certificate.revoked());
        assertEquals(new BigDecimal("1.5"), certificate.coefficientA());
    }

    @Test
    void createWithInvalidIntervalReturns400() {
        ApiException ex = assertThrows(ApiException.class,
                () -> create("inst-1", "2026-02-01T00:00:00Z", "2026-01-01T00:00:00Z"));
        assertEquals(400, ex.status());

        ApiException equal = assertThrows(ApiException.class,
                () -> create("inst-1", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"));
        assertEquals(400, equal.status());
    }

    @Test
    void overlappingIntervalsReturn409() {
        create("inst-1", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z");
        // 部分重叠（前段）
        assertEquals(409, assertThrows(ApiException.class,
                () -> create("inst-1", "2025-12-01T00:00:00Z", "2026-01-15T00:00:00Z")).status());
        // 部分重叠（后段）
        assertEquals(409, assertThrows(ApiException.class,
                () -> create("inst-1", "2026-01-15T00:00:00Z", "2026-03-01T00:00:00Z")).status());
        // 包含
        assertEquals(409, assertThrows(ApiException.class,
                () -> create("inst-1", "2025-12-01T00:00:00Z", "2026-03-01T00:00:00Z")).status());
        // 被包含
        assertEquals(409, assertThrows(ApiException.class,
                () -> create("inst-1", "2026-01-10T00:00:00Z", "2026-01-20T00:00:00Z")).status());
    }

    @Test
    void adjacentIntervalsAreAllowed() {
        create("inst-1", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z");
        // 右邻接：前一张的 validTo 等于后一张的 validFrom，左闭右开下不重叠
        Certificate next = create("inst-1", "2026-02-01T00:00:00Z", "2026-03-01T00:00:00Z");
        assertTrue(next.id() > 0);
        // 左邻接
        Certificate previous = create("inst-1", "2025-12-01T00:00:00Z", "2026-01-01T00:00:00Z");
        assertTrue(previous.id() > 0);
    }

    @Test
    void differentInstrumentsMayOverlap() {
        create("inst-1", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z");
        Certificate other = create("inst-2", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z");
        assertTrue(other.id() > 0);
    }

    @Test
    void revokedCertificateDoesNotBlockNewInterval() {
        Certificate certificate = create("inst-1", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z");
        service.revoke(certificate.id());
        Certificate replacement = create("inst-1", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z");
        assertTrue(replacement.id() > 0);
    }

    @Test
    void revokeSucceedsAndIsIdempotentConflict() {
        Certificate certificate = create("inst-1", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z");
        Certificate revoked = service.revoke(certificate.id());
        assertTrue(revoked.revoked());
        assertEquals(T0, revoked.revokedAt());
        // 重复撤销返回 409
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.revoke(certificate.id())).status());
    }

    @Test
    void revokeMissingCertificateReturns404() {
        assertEquals(404, assertThrows(ApiException.class, () -> service.revoke(999L)).status());
    }

    @Test
    void concurrentOverlappingCreatesAllowAtMostOneSuccess() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    create("inst-1", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z");
                    return true;
                } catch (ApiException ex) {
                    assertEquals(409, ex.status());
                    return false;
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        int successes = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(10, TimeUnit.SECONDS)) {
                successes++;
            }
        }
        pool.shutdownNow();
        assertEquals(1, successes, "并发创建重叠证书时最多一张成功");
        assertEquals(1, repositories.certificates().findByInstrument("inst-1").size());
    }
}
