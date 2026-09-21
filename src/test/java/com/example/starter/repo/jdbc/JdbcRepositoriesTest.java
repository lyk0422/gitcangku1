package com.example.starter.repo.jdbc;

import com.example.starter.domain.Asset;
import com.example.starter.domain.Channel;
import com.example.starter.domain.Draft;
import com.example.starter.domain.DraftSegment;
import com.example.starter.domain.Grant;
import com.example.starter.domain.PublishedSchedule;
import com.example.starter.domain.RequestRecord;
import com.example.starter.repo.DuplicateKeyException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC 仓储集成测试：以 H2（MySQL 兼容模式）执行真实 schema.sql，
 * 验证建表语句、CRUD 与乐观版本 CAS 语义。
 */
class JdbcRepositoriesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);

    private JdbcAssetRepository assets;
    private JdbcChannelRepository channels;
    private JdbcGrantRepository grants;
    private JdbcDraftRepository drafts;
    private JdbcPublishedRepository published;
    private JdbcRequestDedupRepository dedup;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assets = new JdbcAssetRepository(jdbc);
        channels = new JdbcChannelRepository(jdbc);
        grants = new JdbcGrantRepository(jdbc);
        drafts = new JdbcDraftRepository(jdbc);
        published = new JdbcPublishedRepository(jdbc);
        dedup = new JdbcRequestDedupRepository(jdbc);
    }

    private static DraftSegment segment(String id, String assetId, long startMs, long endMs) {
        return new DraftSegment(id, assetId, Instant.ofEpochMilli(startMs),
                Instant.ofEpochMilli(endMs));
    }

    @Test
    void assetInsertFindAndDuplicate() {
        assets.insert(new Asset("a1", 60_000));
        assertEquals(60_000, assets.findById("a1").orElseThrow().durationMs());
        assertTrue(assets.findById("missing").isEmpty());
        assertThrows(DuplicateKeyException.class, () -> assets.insert(new Asset("a1", 1)));
    }

    @Test
    void channelInsertFindAndDuplicate() {
        channels.insert(new Channel("c1", "a1"));
        assertEquals("a1", channels.findById("c1").orElseThrow().fallbackAssetId());
        assertThrows(DuplicateKeyException.class,
                () -> channels.insert(new Channel("c1", "a2")));
    }

    @Test
    void grantLifecycle() {
        Grant grant = new Grant("g1", "c1", "a1",
                Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(2_000), false);
        grants.insert(grant);
        assertFalse(grants.findById("g1").orElseThrow().revoked());
        // 覆盖判定：区间左闭右开
        assertTrue(grants.existsCovering("c1", "a1",
                Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(2_000)));
        assertFalse(grants.existsCovering("c1", "a1",
                Instant.ofEpochMilli(999), Instant.ofEpochMilli(2_000)));
        assertFalse(grants.existsCovering("c1", "a1",
                Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(2_001)));
        assertFalse(grants.existsCovering("c1", "other",
                Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(2_000)));
        // 撤销后不再覆盖
        grants.markRevoked("g1");
        assertTrue(grants.findById("g1").orElseThrow().revoked());
        assertFalse(grants.existsCovering("c1", "a1",
                Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(2_000)));
    }

    @Test
    void draftReplaceCompareAndSwap() {
        List<DraftSegment> v1Segments = List.of(segment("s1", "a1", 0, 60_000));
        assertTrue(drafts.replace("c1", DAY, 0, 1, v1Segments));
        // 重复创建失败
        assertFalse(drafts.replace("c1", DAY, 0, 1, v1Segments));
        // 版本不符失败
        assertFalse(drafts.replace("c1", DAY, 5, 6, v1Segments));
        // 版本匹配整份替换
        List<DraftSegment> v2Segments = List.of(segment("s2", "a1", 60_000, 120_000),
                segment("s3", "a1", 120_000, 180_000));
        assertTrue(drafts.replace("c1", DAY, 1, 2, v2Segments));

        Draft draft = drafts.find("c1", DAY).orElseThrow();
        assertEquals(2, draft.version());
        assertEquals(2, draft.segments().size());
        assertEquals("s2", draft.segments().get(0).segmentId());
        assertEquals("s3", draft.segments().get(1).segmentId());
        assertTrue(drafts.find("c1", DAY.plusDays(1)).isEmpty());
    }

    @Test
    void publishCompareAndSwap() {
        List<DraftSegment> segments = List.of(segment("s1", "a1", 0, 60_000));
        assertTrue(published.publish("c1", DAY, 0, 1, 1, segments));
        assertFalse(published.publish("c1", DAY, 0, 1, 1, segments));
        assertFalse(published.publish("c1", DAY, 3, 4, 1, segments));
        assertTrue(published.publish("c1", DAY, 1, 2, 2,
                List.of(segment("s2", "a1", 60_000, 120_000))));

        PublishedSchedule schedule = published.find("c1", DAY).orElseThrow();
        assertEquals(2, schedule.version());
        assertEquals(2, schedule.draftVersion());
        assertEquals(1, schedule.segments().size());
        assertEquals("s2", schedule.segments().get(0).segmentId());
    }

    @Test
    void requestDedupLifecycle() {
        dedup.insertPlaceholder("r1", "REPLACE_DRAFT", "fp-1");
        // 同 requestId 重复占位冲突
        assertThrows(DuplicateKeyException.class,
                () -> dedup.insertPlaceholder("r1", "REPLACE_DRAFT", "fp-1"));
        dedup.complete("r1", "{\"version\":1}");
        RequestRecord record = dedup.find("r1").orElseThrow();
        assertEquals("fp-1", record.fingerprint());
        assertEquals("{\"version\":1}", record.resultJson());
        assertTrue(dedup.find("missing").isEmpty());
    }
}
