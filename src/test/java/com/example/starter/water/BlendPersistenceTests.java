package com.example.starter.water;

import com.example.starter.water.BlendRepository.BlendApplicationRow;
import com.example.starter.water.BlendRepository.BlendSourceItemRow;
import com.example.starter.water.BlendRepository.BlendSourceRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多来源水质混配持久化验证：同一 JVM 内使用独立命名 H2 内存库（MODE=MySQL），
 * 建表写入后以全新连接与仓储实例重读，验证预占口径、唯一约束、CHECK 约束与取消释放。
 * 本题不要求跨进程重启恢复。
 */
class BlendPersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:blend-persist-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";

    private SimpleDriverDataSource newDatabase() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        return dataSource;
    }

    @Test
    void reservedApplicationsAndItemsAreReadableFromNewConnection() throws Exception {
        SimpleDriverDataSource first = newDatabase();
        BlendRepository repo1 = new BlendRepository(new JdbcTemplate(first));
        long jiaId = repo1.insertSource("src-jia", "来源甲", new BigDecimal("2.0000"),
                new BigDecimal("10.000"), 1L);
        long yiId = repo1.insertSource("src-yi", "来源乙", new BigDecimal("4.0000"),
                new BigDecimal("10.000"), 1L);
        repo1.addReservedVolume(jiaId, new BigDecimal("6.000"), 2L);
        repo1.addReservedVolume(yiId, new BigDecimal("4.000"), 2L);
        // 加权 (6×2 + 4×4)/10 = 2.8
        repo1.insertApplication("req-1", "user-1", "P", new BigDecimal("3.0000"),
                new BigDecimal("10.000"), new BigDecimal("2.8000"), "alice", 2L);
        repo1.insertItem("req-1", "src-jia", new BigDecimal("6.000"), new BigDecimal("2.0000"),
                new BigDecimal("2.8000"), 2L);
        repo1.insertItem("req-1", "src-yi", new BigDecimal("4.000"), new BigDecimal("4.0000"),
                new BigDecimal("2.8000"), 2L);

        // 全新连接与仓储实例重读
        BlendRepository repo2 = new BlendRepository(
                new JdbcTemplate(new SimpleDriverDataSource(
                        first.getDriver(), first.getUrl(), first.getUsername(), first.getPassword())));
        BlendSourceRow jia = repo2.findSourceByKey("src-jia");
        assertNotNull(jia);
        assertEquals(new BigDecimal("6.000"), jia.reservedVolume());
        assertEquals(0, jia.totalQuota().subtract(jia.reservedVolume())
                .compareTo(new BigDecimal("4.000")));
        BlendApplicationRow application = repo2.findApplicationByRequestId("req-1");
        assertNotNull(application);
        assertEquals("RESERVED", application.status());
        assertEquals(new BigDecimal("2.8000"), application.weightedIndex());
        List<BlendSourceItemRow> items = repo2.listItems("req-1");
        assertEquals(2, items.size());
        assertEquals("src-jia", items.get(0).sourceKey());
        assertEquals("RESERVED", items.get(0).reservedStatus());

        // 取消：释放来源预占、明细置 RELEASED，申请与明细快照保留
        int released = repo2.releaseItems("req-1");
        assertEquals(2, released);
        repo2.releaseReservedVolume(jiaId, new BigDecimal("6.000"), 3L);
        repo2.releaseReservedVolume(yiId, new BigDecimal("4.000"), 3L);
        repo2.markApplicationCancelled(application.id(), 3L);
        BlendApplicationRow cancelled = repo2.findApplicationByRequestId("req-1");
        assertEquals("CANCELLED", cancelled.status());
        assertEquals(new BigDecimal("2.8000"), cancelled.weightedIndex());
        assertEquals(3L, cancelled.cancelledNanos());
        assertEquals(0, repo2.findSourceByKey("src-jia").reservedVolume().compareTo(BigDecimal.ZERO));
        for (BlendSourceItemRow item : repo2.listItems("req-1")) {
            assertEquals("RELEASED", item.reservedStatus());
            // 历史体积与指标不重算
            assertNotNull(item.qualityIndex());
            assertTrue(item.volume().signum() > 0);
        }
        // 已全部 RELEASED 时重复释放不影响行数
        assertEquals(0, repo2.releaseItems("req-1"));
    }

    @Test
    void uniqueAndCheckConstraintsAreEnforcedByH2() throws Exception {
        SimpleDriverDataSource dataSource = newDatabase();
        BlendRepository repo = new BlendRepository(new JdbcTemplate(dataSource));
        repo.insertSource("src-unique", "来源", new BigDecimal("2"), new BigDecimal("5.000"), 1L);
        // 来源业务键唯一约束
        assertThrows(DuplicateKeyException.class, () -> repo.insertSource("src-unique", "来源2",
                new BigDecimal("3"), new BigDecimal("5.000"), 2L));

        // 预占超过总配额违反 CHECK（直接写库验证约束真实存在）
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThrows(Exception.class, () -> jdbc.update(
                "UPDATE blend_source SET reserved_volume = total_quota + 1 WHERE source_key = ?",
                "src-unique"));

        // requestId 唯一约束
        repo.insertApplication("req-unique", "u", "P", new BigDecimal("3"), new BigDecimal("1"),
                new BigDecimal("2"), "alice", 1L);
        assertThrows(DuplicateKeyException.class, () -> repo.insertApplication("req-unique", "u2", "P2",
                new BigDecimal("4"), new BigDecimal("2"), new BigDecimal("3"), "bob", 2L));
        repo.insertItem("req-unique", "src-unique", new BigDecimal("1"), new BigDecimal("2"),
                new BigDecimal("2"), 1L);
        // 同一申请内来源明细唯一约束
        assertThrows(DuplicateKeyException.class, () -> repo.insertItem("req-unique", "src-unique",
                new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("2"), 1L));
        // 外键约束：不允许引用不存在的来源
        assertThrows(Exception.class, () -> repo.insertItem("req-unique", "src-missing",
                new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("2"), 1L));
    }

    @Test
    void lockSourcesByKeysReturnsRowsInSortedOrder() throws Exception {
        SimpleDriverDataSource dataSource = newDatabase();
        BlendRepository repo = new BlendRepository(new JdbcTemplate(dataSource));
        PlatformTransactionManager txManager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        repo.insertSource("src-b", "乙", new BigDecimal("4"), new BigDecimal("10"), 1L);
        repo.insertSource("src-a", "甲", new BigDecimal("2"), new BigDecimal("10"), 1L);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        List<BlendSourceRow> locked = tx.execute(status ->
                repo.lockSourcesByKeys(List.of("src-b", "src-a")));
        assertNotNull(locked);
        assertEquals(2, locked.size());
        assertEquals("src-a", locked.get(0).sourceKey());
        assertEquals("src-b", locked.get(1).sourceKey());
    }
}
