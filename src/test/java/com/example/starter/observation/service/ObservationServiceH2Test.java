package com.example.starter.observation.service;

import com.example.starter.observation.dto.CreateRequest;
import com.example.starter.observation.dto.DeleteRequest;
import com.example.starter.observation.dto.ObservationResponse;
import com.example.starter.observation.dto.OfflineSubmitRequest;
import com.example.starter.observation.exception.ConflictException;
import com.example.starter.observation.exception.ObservationGoneException;
import com.example.starter.observation.exception.ObservationNotFoundException;
import com.example.starter.observation.exception.VersionMismatchException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 基于真实 H2（MODE=MySQL）验证离线三方合并主流程、失败分支及并发/幂等边界。
 */
@SpringBootTest
@ActiveProfiles("test")
class ObservationServiceH2Test {

    @Autowired
    private ObservationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM dedup_record");
    }

    private CreateRequest createRequest(String id, String location, String reading, String remark) {
        CreateRequest request = new CreateRequest();
        request.setObservationId(id);
        request.setLocation(location);
        request.setReading(reading);
        request.setRemark(remark);
        return request;
    }

    private OfflineSubmitRequest submitRequest(int baseVersion, String location, String reading, String remark) {
        OfflineSubmitRequest request = new OfflineSubmitRequest();
        request.setBaseVersion(baseVersion);
        request.setLocation(location);
        request.setReading(reading);
        request.setRemark(remark);
        return request;
    }

    @Test
    void createProducesVersionOneAndFastForwardSubmitAddsVersion() {
        WriteResult created = service.create(createRequest("obs-1", "A点", "1.500", "初测"), "req-c1");
        assertThat(created.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.body().getVersion()).isEqualTo(1);
        assertThat(created.body().isDeleted()).isFalse();

        WriteResult submitted = service.offlineSubmit("obs-1",
                submitRequest(1, "A点", "2.000", "初测"), "req-s1");
        assertThat(submitted.status()).isEqualTo(HttpStatus.OK);
        assertThat(submitted.body().getVersion()).isEqualTo(2);
        assertThat(submitted.body().getReading()).isEqualTo("2.000");
        assertThat(service.getVersion("obs-1", 1).getReading()).isEqualTo("1.500");
    }

    @Test
    void threeWayMergeKeepsCurrentWhenCandidateUnchangedAndAcceptsWhenCurrentUnchanged() {
        service.create(createRequest("obs-2", "A点", "1.000", "备注"), "req-c2");
        // 服务端当前已到 v2（地点被改），客户端基于 v1 且候选地点未改：保留当前地点。
        service.offlineSubmit("obs-2", submitRequest(1, "B点", "1.000", "备注"), "req-s2a");
        assertThat(service.getCurrent("obs-2").getLocation()).isEqualTo("B点");

        // 客户端基于 v1 只改读数；当前地点变了但读数未变：接受候选读数，同时保留当前地点。
        WriteResult merged = service.offlineSubmit("obs-2",
                submitRequest(1, "A点", "9.000", "备注"), "req-s2b");
        ObservationResponse body = merged.body();
        assertThat(body.getVersion()).isEqualTo(3);
        assertThat(body.getLocation()).isEqualTo("B点");
        assertThat(body.getReading()).isEqualTo("9.000");
    }

    @Test
    void bothSidesChangingToSameValueIsAcceptedAndReadingComparedNumerically() {
        service.create(createRequest("obs-3", "A点", "1.500", "备注"), "req-c3");
        // 服务端 v2：读数数值改为 2.500（字符串与 1.500 数值不同，确为新版本）。
        service.offlineSubmit("obs-3", submitRequest(1, "A点", "2.500", "备注"), "req-s3a");
        // 候选基于 v1：读数改为 2.5，与当前 2.500 数值相同（两边改成相同值，按数值判定接受候选原文）；
        // 备注候选改、当前未改，接受候选。
        WriteResult merged = service.offlineSubmit("obs-3",
                submitRequest(1, "A点", "2.5", "新备注"), "req-s3b");
        assertThat(merged.body().getVersion()).isEqualTo(3);
        assertThat(merged.body().getReading()).isEqualTo("2.5");
        assertThat(merged.body().getRemark()).isEqualTo("新备注");
    }

    @Test
    void conflictingFieldsCause409WithCurrentVersionAndNoPartialWrite() {
        service.create(createRequest("obs-4", "A点", "1.000", "备注"), "req-c4");
        // 当前 v2：地点与读数都被改。
        service.offlineSubmit("obs-4", submitRequest(1, "B点", "2.000", "备注"), "req-s4a");
        // 候选基于 v1，地点改成与当前不同的值，读数候选未改（保留当前），备注候选改、当前未改（接受）。
        assertThatThrownBy(() -> service.offlineSubmit("obs-4",
                submitRequest(1, "C点", "1.000", "候选备注"), "req-s4b"))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.getCurrentVersion()).isEqualTo(2);
                    assertThat(ex.getConflictFields()).containsExactly("location");
                });

        ObservationResponse current = service.getCurrent("obs-4");
        assertThat(current.getVersion()).isEqualTo(2);
        assertThat(current.getLocation()).isEqualTo("B点");
        assertThat(current.getRemark()).isEqualTo("备注");
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-4'", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void mergeIdenticalToCurrentReturnsCurrentWithoutNewVersion() {
        service.create(createRequest("obs-5", "A点", "1.000", "备注"), "req-c5");
        service.offlineSubmit("obs-5", submitRequest(1, "B点", "2.000", "新备注"), "req-s5a");
        // 候选基于 v1，把字段都改成与当前 v2 完全相同的值（读数按数值相等）。
        WriteResult result = service.offlineSubmit("obs-5",
                submitRequest(1, "B点", "2", "新备注"), "req-s5b");
        assertThat(result.status()).isEqualTo(HttpStatus.OK);
        assertThat(result.body().getVersion()).isEqualTo(2);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-5'", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void missingBaselineVersionReturns404() {
        service.create(createRequest("obs-6", "A点", "1.000", "备注"), "req-c6");
        assertThatThrownBy(() -> service.offlineSubmit("obs-6",
                submitRequest(99, "A点", "1.000", "备注"), "req-s6"))
                .isInstanceOf(ObservationNotFoundException.class);
        assertThatThrownBy(() -> service.getVersion("obs-6", 99))
                .isInstanceOf(ObservationNotFoundException.class);
        assertThatThrownBy(() -> service.getCurrent("missing"))
                .isInstanceOf(ObservationNotFoundException.class);
    }

    @Test
    void deleteCreatesTombstoneAndRejectsFurtherWritesWith410() {
        service.create(createRequest("obs-7", "A点", "1.000", "备注"), "req-c7");

        assertThatThrownBy(() -> service.delete("obs-7", new DeleteRequest() {{
            setExpectedVersion(99);
        }}, "req-d7bad")).isInstanceOf(VersionMismatchException.class);

        DeleteRequest delete = new DeleteRequest();
        delete.setExpectedVersion(1);
        WriteResult deleted = service.delete("obs-7", delete, "req-d7");
        assertThat(deleted.body().isDeleted()).isTrue();
        assertThat(deleted.body().getVersion()).isEqualTo(2);
        assertThat(deleted.body().getLocation()).isNull();

        ObservationResponse current = service.getCurrent("obs-7");
        assertThat(current.isDeleted()).isTrue();
        assertThat(current.getLocation()).isNull();
        assertThat(current.getReading()).isNull();
        assertThat(current.getRemark()).isNull();
        assertThat(service.getVersion("obs-7", 1).getLocation()).isEqualTo("A点");

        assertThatThrownBy(() -> service.offlineSubmit("obs-7",
                submitRequest(2, "A点", "1.000", "备注"), "req-s7"))
                .isInstanceOf(ObservationGoneException.class);
        DeleteRequest delete2 = new DeleteRequest();
        delete2.setExpectedVersion(2);
        assertThatThrownBy(() -> service.delete("obs-7", delete2, "req-d7b"))
                .isInstanceOf(ObservationGoneException.class);
        // 墓碑后创建同 ID 也拒绝，防止复活。
        assertThatThrownBy(() -> service.create(createRequest("obs-7", "X", "1", "y"), "req-c7b"))
                .isInstanceOf(ObservationGoneException.class);
    }

    @Test
    void duplicateCreateReturns409() {
        service.create(createRequest("obs-8", "A点", "1.000", "备注"), "req-c8");
        assertThatThrownBy(() -> service.create(createRequest("obs-8", "B点", "2.000", "x"), "req-c8b"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void replaySameRequestIdAndParamsReturnsOriginalResultWithoutNewVersion() {
        CreateRequest request = createRequest("obs-9", "A点", "1.000", "备注");
        WriteResult first = service.create(request, "req-idem-1");
        WriteResult replay = service.create(request, "req-idem-1");
        assertThat(replay.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.body().getVersion()).isEqualTo(1);
        assertThat(replay.body().getCreatedAt()).isEqualTo(first.body().getCreatedAt());
        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-9'", Integer.class);
        Integer dedupCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dedup_record WHERE request_id = 'req-idem-1'", Integer.class);
        assertThat(versionCount).isEqualTo(1);
        assertThat(dedupCount).isEqualTo(1);

        OfflineSubmitRequest submit = submitRequest(1, "A点", "3.000", "备注");
        WriteResult submit1 = service.offlineSubmit("obs-9", submit, "req-idem-2");
        WriteResult submit2 = service.offlineSubmit("obs-9", submit, "req-idem-2");
        assertThat(submit2.body().getVersion()).isEqualTo(2);
        assertThat(service.getCurrent("obs-9").getVersion()).isEqualTo(2);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() {
        service.create(createRequest("obs-10", "A点", "1.000", "备注"), "req-idem-3");
        service.offlineSubmit("obs-10", submitRequest(1, "A点", "2.000", "备注"), "req-idem-4");
        assertThatThrownBy(() -> service.offlineSubmit("obs-10",
                submitRequest(1, "A点", "5.000", "备注"), "req-idem-4"))
                .isInstanceOf(ConflictException.class);
        assertThat(service.getCurrent("obs-10").getReading()).isEqualTo("2.000");
    }

    @Test
    void failedRequestDoesNotOccupyIdempotencyKey() {
        service.create(createRequest("obs-11", "A点", "1.000", "备注"), "req-c11");
        // 基线版本不存在导致失败。
        assertThatThrownBy(() -> service.offlineSubmit("obs-11",
                submitRequest(40, "A点", "2.000", "备注"), "req-reuse"))
                .isInstanceOf(ObservationNotFoundException.class);
        Integer dedupCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dedup_record WHERE request_id = 'req-reuse'", Integer.class);
        assertThat(dedupCount).isEqualTo(0);
        // 同一 requestId 以合法参数重试成功。
        WriteResult retry = service.offlineSubmit("obs-11",
                submitRequest(1, "A点", "2.000", "备注"), "req-reuse");
        assertThat(retry.status()).isEqualTo(HttpStatus.OK);
        assertThat(retry.body().getVersion()).isEqualTo(2);
    }

    @Test
    void concurrentSubmitsBasedOnSameVersionAreJudgedAgainstLatestAndDoNotOverwrite() throws Exception {
        service.create(createRequest("obs-12", "A点", "1.000", "备注"), "req-c12");

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    awaitQuietly(ready, start);
                    try {
                        service.offlineSubmit("obs-12",
                                submitRequest(1, "A点", (100 + idx) + ".000", "备注"),
                                "req-concurrent-" + idx);
                        success.incrementAndGet();
                    } catch (ConflictException ex) {
                        conflict.incrementAndGet();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        ObservationResponse current = service.getCurrent("obs-12");
        assertThat(current.getVersion()).isEqualTo(2);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-12'", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void concurrentModifyAndDeleteNeverResurrectsTombstone() throws Exception {
        int rounds = 6;
        for (int r = 0; r < rounds; r++) {
            final int round = r;
            final String obsId = "obs-13-" + round;
            service.create(createRequest(obsId, "A点", "1.000", "备注"), "req-c13-" + round);

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> deleteFuture = pool.submit(() -> {
                    awaitQuietly(null, start);
                    DeleteRequest delete = new DeleteRequest();
                    delete.setExpectedVersion(1);
                    try {
                        service.delete(obsId, delete, "req-d13-" + round);
                    } catch (RuntimeException ignored) {
                        // 与离线提交竞争时可能 409/410，由最终不变量保证正确性。
                    }
                });
                Future<?> submitFuture = pool.submit(() -> {
                    awaitQuietly(null, start);
                    try {
                        service.offlineSubmit(obsId,
                                submitRequest(1, "A点", "8.000", "备注"),
                                "req-s13-" + round);
                    } catch (RuntimeException ignored) {
                        // 与删除竞争时可能 409/410，由最终不变量保证正确性。
                    }
                });
                start.countDown();
                deleteFuture.get(30, TimeUnit.SECONDS);
                submitFuture.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            // 核心不变量：若墓碑存在，墓碑必为最高版本，其后绝不能出现存活版本（不得复活）。
            Integer maxVersion = jdbcTemplate.queryForObject(
                    "SELECT MAX(version) FROM observation_version WHERE observation_id = ?",
                    Integer.class, obsId);
            Integer tombstoneCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM observation_version WHERE observation_id = ? AND deleted = 1",
                    Integer.class, obsId);
            if (tombstoneCount > 0) {
                Integer deletedAtMax = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM observation_version WHERE observation_id = ? AND version = ? AND deleted = 1",
                        Integer.class, obsId, maxVersion);
                assertThat(deletedAtMax).isEqualTo(1);
                assertThat(service.getCurrent(obsId).isDeleted()).isTrue();
            }
        }
    }

    @Test
    void concurrentSameRequestIdReplaysSingleSuccessfulOutcome() throws Exception {
        int threads = 6;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<WriteResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    awaitQuietly(ready, start);
                    return service.create(createRequest("obs-14", "A点", "1.000", "备注"), "req-race-same");
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<WriteResult> future : futures) {
                WriteResult result = future.get(30, TimeUnit.SECONDS);
                assertThat(result.status()).isEqualTo(HttpStatus.CREATED);
                assertThat(result.body().getVersion()).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-14'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    private static void awaitQuietly(CountDownLatch ready, CountDownLatch start) {
        try {
            if (ready != null) {
                ready.countDown();
            }
            start.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
