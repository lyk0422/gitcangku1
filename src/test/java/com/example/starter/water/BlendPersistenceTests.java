package com.example.starter.water;

import com.example.starter.water.BlendRepository.BlendApplicationRow;
import com.example.starter.water.BlendRepository.BlendItemRow;
import com.example.starter.water.BlendRepository.BlendSourceRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 水质混配持久化验证：同一 JVM 内使用独立命名 H2 内存库（MODE=MySQL），
 * 验证建表写入、快照读取、预占汇总口径（取消后不计）与唯一/CHECK 约束真实生效。
 */
class BlendPersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:blend-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private SimpleDriverDataSource newDatabase(String url) {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return dataSource;
    }

    @Test
    void reservationSumExcludesCancelledAndSnapshotsSurviveNewConnection() {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        SimpleDriverDataSource dataSource = newDatabase(url);
        BlendRepository repo1 = new BlendRepository(new JdbcTemplate(dataSource));
        repo1.insertSource("src-jia", "来源甲", new BigDecimal("10.000"), new BigDecimal("2.000000"), 1L);
        long app1 = repo1.insertApplication("req-1", "user-1", "灌溉", new BigDecimal("3.000000"),
                new BigDecimal("20.000"), new BigDecimal("3.000000"), "alice", 2L);
        repo1.insertItem(app1, "src-jia", new BigDecimal("6.000"), new BigDecimal("2.000000"), 1, 2L);
        long app2 = repo1.insertApplication("req-2", "user-1", "灌溉", new BigDecimal("3.000000"),
                new BigDecimal("6.000"), new BigDecimal("3.000000"), "alice", 3L);
        repo1.insertItem(app2, "src-jia", new BigDecimal("3.000"), new BigDecimal("2.000000"), 1, 3L);

        // 两笔 RESERVED：预占合计 9
        assertEquals(new BigDecimal("9.000"), repo1.sumReservedVolumeBySource("src-jia"));
        // 取消 app1：其 6 立即释放，只剩 3
        repo1.cancelApplication(app1, 4L);
        assertEquals(new BigDecimal("3.000"), repo1.sumReservedVolumeBySource("src-jia"));

        // 全新连接与仓储实例：快照仍在，且取消申请的明细保留但不计入预占
        BlendRepository repo2 = new BlendRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        BlendSourceRow source = repo2.findSourceByKey("src-jia");
        assertNotNull(source);
        assertEquals(new BigDecimal("10.000"), source.quotaVolume());
        assertEquals(new BigDecimal("2.000000"), source.qualityIndex());

        BlendApplicationRow cancelled = repo2.findApplicationByRequestId("req-1");
        assertEquals("CANCELLED", cancelled.status());
        assertNotNull(cancelled.cancelledNanos());
        // 历史加权依据保留
        assertEquals(new BigDecimal("3.000000"), cancelled.weightedIndex());
        List<BlendItemRow> items = repo2.listItemsByApplicationId(app1);
        assertEquals(1, items.size());
        assertEquals(new BigDecimal("6.000"), items.get(0).volume());
        assertEquals(new BigDecimal("2.000000"), items.get(0).qualityIndex());
        assertEquals(new BigDecimal("3.000"), repo2.sumReservedVolumeBySource("src-jia"));

        BlendApplicationRow active = repo2.findApplicationByRequestId("req-2");
        assertEquals("RESERVED", active.status());
        assertNull(active.cancelledNanos());
    }

    @Test
    void nullQualityIndexIsStoredAndReadBackAsNull() {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        BlendRepository repo = new BlendRepository(new JdbcTemplate(newDatabase(url)));
        repo.insertSource("src-null", "无指标来源", new BigDecimal("5.000"), null, 1L);
        BlendSourceRow row = repo.findSourceByKey("src-null");
        assertNull(row.qualityIndex());
        assertEquals(new BigDecimal("0"), repo.sumReservedVolumeBySource("src-null"));
    }

    @Test
    void constraintsAreEnforcedByH2() {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        JdbcTemplate jdbc = new JdbcTemplate(newDatabase(url));
        BlendRepository repo = new BlendRepository(jdbc);
        repo.insertSource("src-c", "来源", new BigDecimal("10.000"), new BigDecimal("1"), 1L);
        long appId = repo.insertApplication("req-c", "u", "p", new BigDecimal("1"),
                new BigDecimal("2.000"), new BigDecimal("1"), "alice", 2L);
        repo.insertItem(appId, "src-c", new BigDecimal("1.000"), new BigDecimal("1"), 1, 2L);

        // 同申请同来源唯一约束
        assertThrows(DataIntegrityViolationException.class, () ->
                repo.insertItem(appId, "src-c", new BigDecimal("1.000"), new BigDecimal("1"), 2, 2L));
        // 来源配额必须大于 0（CHECK）
        assertThrows(DataIntegrityViolationException.class, () ->
                repo.insertSource("src-zero", "零配额", new BigDecimal("0.000"), new BigDecimal("1"), 1L));
        // 明细体积必须大于 0（CHECK）
        assertThrows(DataIntegrityViolationException.class, () ->
                repo.insertItem(appId, "src-other", new BigDecimal("0.000"), new BigDecimal("1"), 3, 2L));
        // requestId 全局唯一
        assertThrows(DataIntegrityViolationException.class, () ->
                repo.insertApplication("req-c", "u", "p", new BigDecimal("1"),
                        new BigDecimal("1.000"), new BigDecimal("1"), "alice", 3L));
    }
}
