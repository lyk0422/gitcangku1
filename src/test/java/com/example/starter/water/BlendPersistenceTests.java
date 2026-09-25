package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.BlendItemRow;
import com.example.starter.water.WaterRepository.BlendSnapshotRow;
import com.example.starter.water.WaterRepository.SourceRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 水质掺配持久化验证：同一 JVM 内使用命名 H2 内存库（MODE=MySQL），建表写入后以全新连接与仓储实例
 * 重新读取，验证水源余量、乐观版本、掺配快照冻结、唯一约束与跨表扣减事务回滚。
 * 不使用 mock 或 Map 代替数据库边界；不要求跨进程重启恢复。
 */
class BlendPersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:blend-persist-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private SimpleDriverDataSource freshDatabase() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        return dataSource;
    }

    private long seedWindowAndAllocation(WaterRepository repo) {
        long windowId = repo.insertWindow("wk-blend", "ch-blend", 1L, 2L, new BigDecimal("10.000"), 1L);
        repo.insertAllocation("ak-blend", windowId, "user-1", new BigDecimal("10.000"),
                new BigDecimal("600.000"), "alice", 2L);
        AllocationRow allocation = repo.findAllocationByKey("ak-blend");
        repo.updateAllocationStatus(allocation.id(), "APPROVED", 3L);
        return windowId;
    }

    @Test
    void sourceAndFrozenSnapshotReadableFromNewConnectionAfterSalinityChange() throws Exception {
        SimpleDriverDataSource first = freshDatabase();
        WaterRepository repo1 = new WaterRepository(new JdbcTemplate(first));
        seedWindowAndAllocation(repo1);
        repo1.insertSource("sk-a", new BigDecimal("6.000"), new BigDecimal("500.000"), 4L);
        repo1.insertSource("sk-b", new BigDecimal("4.000"), new BigDecimal("800.000"), 4L);

        // 一次核销：6@500 + 4@800 = 10，加权 620 mg/L
        long snapshotId = repo1.insertBlendSnapshot("bk-1", "ak-blend", "alice", new BigDecimal("10.000"),
                new BigDecimal("620.000000"), new BigDecimal("600.000"), 1L, 5L);
        repo1.insertBlendItem(snapshotId, "sk-a", new BigDecimal("6.000"), new BigDecimal("500.000"), 0);
        repo1.insertBlendItem(snapshotId, "sk-b", new BigDecimal("4.000"), new BigDecimal("800.000"), 1);
        repo1.decrementSourceAmount(repo1.findSourceByKey("sk-a").id(), new BigDecimal("6.000"));
        repo1.decrementSourceAmount(repo1.findSourceByKey("sk-b").id(), new BigDecimal("4.000"));
        AllocationRow allocation = repo1.findAllocationByKey("ak-blend");
        repo1.decrementHeldAmount(allocation.id(), new BigDecimal("10.000"), 5L);

        // 之后修改水源 sk-a 盐度（携带旧版本 0 -> 成功，版本变 1）
        int updated = repo1.updateSourceSalinityIfVersion("sk-a", new BigDecimal("900.000"), 0L, 6L);
        assertEquals(1, updated);
        // 再用旧版本修改 -> 0 行，乐观并发裁决
        assertEquals(0, repo1.updateSourceSalinityIfVersion("sk-a", new BigDecimal("950.000"), 0L, 7L));

        // 全新连接与仓储实例读取同一 JVM 内状态
        String url = first.getUrl();
        WaterRepository repo2 = new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));

        SourceRow sourceA = repo2.findSourceByKey("sk-a");
        assertNotNull(sourceA);
        assertEquals(new BigDecimal("0.000"), sourceA.availableAmount());
        assertEquals(new BigDecimal("900.000"), sourceA.salinity());
        assertEquals(1L, sourceA.version());

        AllocationRow writtenOff = repo2.findAllocationByKey("ak-blend");
        assertEquals(0, writtenOff.heldAmount().compareTo(BigDecimal.ZERO));
        assertEquals(2L, writtenOff.version(), "扣减后申请版本应自增到 2");

        // 历史快照头与明细盐度冻结在核销时刻，不被后续盐度修改改写
        BlendSnapshotRow snapshot = repo2.findBlendSnapshotByKey("bk-1");
        assertNotNull(snapshot);
        assertEquals(new BigDecimal("620.000000"), snapshot.weightedSalinity());
        assertEquals(new BigDecimal("600.000"), snapshot.salinityLimit());
        assertEquals(1L, snapshot.allocationVersion());
        List<BlendItemRow> items = repo2.listBlendItems(snapshot.id());
        assertEquals(2, items.size());
        assertEquals("sk-a", items.get(0).sourceKey());
        assertEquals(new BigDecimal("500.000"), items.get(0).salinity());
        assertEquals(new BigDecimal("6.000"), items.get(0).amount());
        assertEquals("sk-b", items.get(1).sourceKey());
        assertEquals(new BigDecimal("800.000"), items.get(1).salinity());
        // 按申请列出快照
        List<BlendSnapshotRow> byAllocation = repo2.listBlendSnapshots("ak-blend");
        assertEquals(1, byAllocation.size());
        assertEquals("bk-1", byAllocation.get(0).blendKey());
    }

    @Test
    void duplicateBlendKeyAndRepeatedSnapshotSourceAreRejected() throws Exception {
        SimpleDriverDataSource dataSource = freshDatabase();
        WaterRepository repo = new WaterRepository(new JdbcTemplate(dataSource));
        seedWindowAndAllocation(repo);
        long snapshotId = repo.insertBlendSnapshot("bk-dup", "ak-blend", "alice",
                new BigDecimal("1.000"), new BigDecimal("500.000000"), null, 1L, 5L);
        repo.insertBlendItem(snapshotId, "sk-a", new BigDecimal("1.000"), new BigDecimal("500.000"), 0);
        // 复用 blend_key -> 唯一约束冲突
        assertThrows(DuplicateKeyException.class, () -> repo.insertBlendSnapshot("bk-dup", "ak-blend",
                "alice", new BigDecimal("1.000"), new BigDecimal("500.000000"), null, 1L, 6L));
        // 同一快照内重复水源 -> 唯一约束冲突
        assertThrows(DuplicateKeyException.class, () -> repo.insertBlendItem(snapshotId, "sk-a",
                new BigDecimal("1.000"), new BigDecimal("500.000"), 1));
        // 取水量非正 -> CHECK 约束冲突
        assertThrows(Exception.class, () -> repo.insertBlendItem(snapshotId, "sk-b",
                BigDecimal.ZERO, new BigDecimal("500.000"), 2));
    }

    @Test
    void sourceAmountCanNeverGoNegativeByCheckConstraint() throws Exception {
        SimpleDriverDataSource dataSource = freshDatabase();
        WaterRepository repo = new WaterRepository(new JdbcTemplate(dataSource));
        repo.insertSource("sk-low", new BigDecimal("5.000"), new BigDecimal("500.000"), 1L);
        long id = repo.findSourceByKey("sk-low").id();
        // 扣 5 合法
        repo.decrementSourceAmount(id, new BigDecimal("5.000"));
        assertEquals(0, repo.findSourceByKey("sk-low").availableAmount().compareTo(BigDecimal.ZERO));
        // 再扣 0.001 违反 chk_water_source_amount
        assertThrows(Exception.class, () -> repo.decrementSourceAmount(id, new BigDecimal("0.001")));
        // 余量仍为 0，未被扣成负数
        assertEquals(0, repo.findSourceByKey("sk-low").availableAmount().compareTo(BigDecimal.ZERO));
    }

    @Test
    void partialFailureRollsBackAllDeductionsAndSnapshotInOneTransaction() throws Exception {
        SimpleDriverDataSource dataSource = freshDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        WaterRepository repo = new WaterRepository(jdbc);
        seedWindowAndAllocation(repo);
        repo.insertSource("sk-x", new BigDecimal("5.000"), new BigDecimal("500.000"), 1L);
        repo.insertSource("sk-y", new BigDecimal("5.000"), new BigDecimal("700.000"), 1L);
        long sourceX = repo.findSourceByKey("sk-x").id();
        long allocationId = repo.findAllocationByKey("ak-blend").id();

        PlatformTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        // 模拟跨表扣减途中失败：先扣 sk-x，再抛错，整单回滚
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            repo.decrementSourceAmount(sourceX, new BigDecimal("3.000"));
            repo.decrementHeldAmount(allocationId, new BigDecimal("3.000"), 9L);
            throw new IllegalStateException("simulated downstream failure");
        }));

        // 回滚后：水源、申请额度均无变化，版本也不推进，无快照
        assertEquals(new BigDecimal("5.000"), repo.findSourceByKey("sk-x").availableAmount());
        assertEquals(new BigDecimal("5.000"), repo.findSourceByKey("sk-y").availableAmount());
        AllocationRow allocation = repo.findAllocationByKey("ak-blend");
        assertEquals(new BigDecimal("10.000"), allocation.heldAmount());
        assertEquals(1L, allocation.version());
        assertNull(repo.findBlendSnapshotByKey("bk-never"));
        assertTrue(repo.listBlendSnapshots("ak-blend").isEmpty());
    }
}
