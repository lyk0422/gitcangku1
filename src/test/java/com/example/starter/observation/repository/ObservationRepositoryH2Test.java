package com.example.starter.observation.repository;

import com.example.starter.observation.model.DedupRecord;
import com.example.starter.observation.model.ObservationSnapshot;
import com.example.starter.observation.model.OperationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 基于真实 H2（MODE=MySQL）验证建表、唯一约束与版本/幂等数据访问，不使用 mock 或 Map。
 */
@SpringBootTest
@ActiveProfiles("test")
class ObservationRepositoryH2Test {

    @Autowired
    private ObservationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM dedup_record");
    }

    @Test
    void insertAndLoadVersionSnapshotsKeepsFullHistory() {
        Instant now = Instant.parse("2026-09-22T01:02:03Z");
        repository.insertVersion(new ObservationSnapshot("obs-1", 1, "A点", "1.500", "初测", false, now));
        repository.insertVersion(new ObservationSnapshot("obs-1", 2, "B点", "2.000", "复测", false, now.plusSeconds(10)));

        ObservationSnapshot latest = repository.findLatest("obs-1").orElseThrow();
        assertThat(latest.getVersion()).isEqualTo(2);
        assertThat(latest.getLocation()).isEqualTo("B点");
        assertThat(latest.isDeleted()).isFalse();

        ObservationSnapshot history = repository.findVersion("obs-1", 1).orElseThrow();
        assertThat(history.getReading()).isEqualTo("1.500");
        assertThat(history.getCreatedAt()).isEqualTo(now);

        // 直接查表确认历史快照不被删除。
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-1'", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void tombstoneCanBeInsertedAndLoadedWithoutBusinessFields() {
        repository.insertVersion(new ObservationSnapshot("obs-2", 1, "A点", "1", "x", false, Instant.now()));
        repository.insertVersion(new ObservationSnapshot("obs-2", 2, null, null, null, true, Instant.now()));

        ObservationSnapshot tombstone = repository.findLatest("obs-2").orElseThrow();
        assertThat(tombstone.isDeleted()).isTrue();
        assertThat(tombstone.getVersion()).isEqualTo(2);
        assertThat(tombstone.getLocation()).isNull();
        assertThat(tombstone.getReading()).isNull();
        assertThat(tombstone.getRemark()).isNull();
    }

    @Test
    void duplicateObservationVersionPairIsRejectedByUniqueConstraint() {
        repository.insertVersion(new ObservationSnapshot("obs-3", 1, "A", "1", "r", false, Instant.now()));
        assertThatThrownBy(() ->
                repository.insertVersion(new ObservationSnapshot("obs-3", 1, "B", "2", "s", false, Instant.now())))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void dedupPendingCompletionAndDeletionLifecycle() {
        Instant now = Instant.parse("2026-09-22T05:00:00Z");
        DedupRecord record = new DedupRecord();
        record.setRequestId("req-1");
        record.setOperation(OperationType.CREATE);
        record.setObservationId("obs-4");
        record.setRequestHash("hash-a");
        record.setCreatedAt(now);
        record.setUpdatedAt(now);

        assertThat(repository.insertDedupPending(record)).isTrue();
        assertThat(repository.insertDedupPending(record)).isFalse();

        DedupRecord loaded = repository.findDedup("req-1").orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo("PENDING");
        assertThat(loaded.getOperation()).isEqualTo(OperationType.CREATE);

        repository.completeDedup("req-1", 201, "{\"version\":1}", now.plusSeconds(1));
        DedupRecord done = repository.findDedup("req-1").orElseThrow();
        assertThat(done.getStatus()).isEqualTo("DONE");
        assertThat(done.getHttpStatus()).isEqualTo(201);
        assertThat(done.getResponseBody()).isEqualTo("{\"version\":1}");

        repository.deleteDedup("req-1");
        assertThat(repository.findDedup("req-1")).isEmpty();
    }
}
