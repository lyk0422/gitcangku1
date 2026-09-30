package com.example.starter.water;

import com.example.starter.water.EmergencyRepository.ConfirmationRow;
import com.example.starter.water.EmergencyRepository.EventRow;
import com.example.starter.water.EmergencyRepository.FlowRow;
import com.example.starter.water.EmergencyRepository.RequestRow;
import com.example.starter.water.EmergencyRepository.ReserveRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 紧急储备持久化验证：同一 JVM 内使用独立命名 H2 内存库（MODE=MySQL），建表写入后以全新连接与
 * 仓储实例重新读取，验证储备守恒、确认记录、核销/归还口径与流水。不验证跨进程重启恢复。
 */
class EmergencyPersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:emergency-persist-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private EmergencyRepository newRepo(String url) {
        return new EmergencyRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
    }

    @Test
    void activationWriteoffReturnAndReserveAreReadableFromNewConnection() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        SimpleDriverDataSource first = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = first.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        // schema.sql 已初始化唯一储备账户 10
        EmergencyRepository repo1 = newRepo(url);
        ReserveRow initial = repo1.lockReserve();
        assertEquals(new BigDecimal("10.000"), initial.totalVolume());
        assertEquals(new BigDecimal("10.000"), initial.remainingVolume());

        // 普通窗口由既有仓储写入（外键依赖）
        WaterRepository water1 = new WaterRepository(new JdbcTemplate(first));
        long windowId = water1.insertWindow("wk-em", "ch-em", 1_000L, 2_000L,
                new BigDecimal("10.000"), 1L);

        repo1.insertEvent("E", "干渠险情", 2L);
        repo1.insertRequest("req-1", "E", windowId, new BigDecimal("6.000"), 3L);
        repo1.insertConfirmation("req-1", 1, "DISPATCH", "jia", 3L);
        // 第二位确认：扣储备、启用、写流水
        repo1.decrementReserve(new BigDecimal("6.000"), 4L);
        repo1.activateRequest("req-1", 4L);
        repo1.insertConfirmation("req-1", 2, "AUDIT", "yi", 4L);
        repo1.insertFlow("req-1", "E", windowId, "ACTIVATION", new BigDecimal("6.000"), "yi", 4L);

        // 第二次读取：全新连接与仓储实例，同一 JVM 内数据仍在
        EmergencyRepository repo2 = newRepo(url);
        ReserveRow afterActivation = repo2.lockReserve();
        assertEquals(new BigDecimal("4.000"), afterActivation.remainingVolume());

        RequestRow request = repo2.findRequestByRequestId("req-1");
        assertNotNull(request);
        assertEquals("ACTIVE", request.status());
        assertEquals(new BigDecimal("6.000"), request.amount());
        assertEquals(new BigDecimal("6.000"), request.outstanding());
        assertNotNull(request.activatedNanos());

        List<ConfirmationRow> confirmations = repo2.listConfirmations("req-1");
        assertEquals(2, confirmations.size());
        assertEquals("DISPATCH", confirmations.get(0).roleCode());
        assertEquals("jia", confirmations.get(0).confirmer());
        assertEquals("AUDIT", confirmations.get(1).roleCode());
        assertEquals("yi", confirmations.get(1).confirmer());

        // 核销 3.5：不回补储备，未结清变 2.5
        repo2.addWrittenOff("req-1", new BigDecimal("3.500"), 5L);
        repo2.insertFlow("req-1", "E", windowId, "WRITEOFF", new BigDecimal("3.500"), "jia", 5L);
        // 归还 2.5：回补储备，请求结清
        repo2.incrementReserve(new BigDecimal("2.500"), 6L);
        repo2.addReturned("req-1", new BigDecimal("2.500"), 6L);
        repo2.insertFlow("req-1", "E", windowId, "RETURN", new BigDecimal("2.500"), "jia", 6L);
        repo2.settleRequest("req-1", 6L);

        EmergencyRepository repo3 = newRepo(url);
        RequestRow settled = repo3.findRequestByRequestId("req-1");
        assertEquals("SETTLED", settled.status());
        assertEquals(new BigDecimal("3.500"), settled.writtenOffAmount());
        assertEquals(new BigDecimal("2.500"), settled.returnedAmount());
        assertEquals(0, settled.outstanding().compareTo(BigDecimal.ZERO));
        // 储备：10 - 6 + 2.5 = 6.5
        assertEquals(new BigDecimal("6.500"), repo3.lockReserve().remainingVolume());
        // 窗口未结清量为 0（SETTLED 不计入）
        assertEquals(0, repo3.sumOutstandingByWindow(windowId).compareTo(BigDecimal.ZERO));
        assertEquals(0, repo3.listActiveRequestsByWindow(windowId).size());

        List<FlowRow> flows = repo3.listFlowsByRequest("req-1");
        assertEquals(3, flows.size());
        assertEquals("ACTIVATION", flows.get(0).flowType());
        assertEquals("WRITEOFF", flows.get(1).flowType());
        assertEquals("RETURN", flows.get(2).flowType());
        assertEquals(3, repo3.listFlowsByWindow(windowId).size());
        // 事件与两位确认人仍可查询
        EventRow event = repo3.findEventByKey("E");
        assertEquals("VALID", event.status());
        assertEquals(1, repo3.listRequestsByEvent("E").size());

        // 作废事件不影响历史
        repo3.invalidateEvent("E", 7L);
        EventRow invalid = newRepo(url).findEventByKey("E");
        assertEquals("INVALID", invalid.status());
        assertNotNull(invalid.invalidatedNanos());
        assertEquals(2, newRepo(url).listConfirmations("req-1").size());
    }

    @Test
    void pendingRequestDoesNotTouchReserveAndIsExcludedFromWindowOutstanding() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        EmergencyRepository repo = new EmergencyRepository(new JdbcTemplate(dataSource));
        WaterRepository water = new WaterRepository(new JdbcTemplate(dataSource));
        long windowId = water.insertWindow("wk-pending", "ch-pending", 1L, 2L,
                new BigDecimal("5.000"), 1L);
        repo.insertEvent("EP", "pending-event", 2L);
        repo.insertRequest("req-p", "EP", windowId, new BigDecimal("4.000"), 3L);
        repo.insertConfirmation("req-p", 1, "R1", "jia", 3L);

        RequestRow pending = repo.findRequestByRequestId("req-p");
        assertEquals("PENDING", pending.status());
        assertNull(pending.activatedNanos());
        // PENDING 不扣储备
        assertEquals(new BigDecimal("10.000"), repo.lockReserve().remainingVolume());
        // PENDING 不计入窗口预占，不出现在 ACTIVE 列表
        assertEquals(0, repo.sumOutstandingByWindow(windowId).compareTo(BigDecimal.ZERO));
        assertTrue(repo.listActiveRequestsByWindow(windowId).isEmpty());
        // 流水汇总为 0
        assertEquals(0, repo.sumFlowByType("ACTIVATION").compareTo(BigDecimal.ZERO));
    }
}
