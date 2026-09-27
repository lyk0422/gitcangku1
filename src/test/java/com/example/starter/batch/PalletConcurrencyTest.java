package com.example.starter.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 托盘并发裁决测试：同一批次并发只能装入一个托盘（唯一约束）、
 * 装运与解除按提交顺序裁决且不留半成品、同命令并发重放结果一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PalletConcurrencyTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM pallet_disposal");
        jdbc.update("DELETE FROM pallet_shipment");
        jdbc.update("DELETE FROM batch_occupancy");
        jdbc.update("DELETE FROM pallet_load");
        jdbc.update("DELETE FROM pallet");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    @Test
    void concurrentLoadSameBatch_onlyOnePalletWins() throws Exception {
        String pallet1 = "PL-C1-" + unique();
        String pallet2 = "PL-C2-" + unique();
        String batch = "BK-PC-" + unique();
        createPallet(pallet1, 0);
        createPallet(pallet2, 0);
        createBatch(batch);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(loadRequest(pallet1, "CK-C1", batch)),
                () -> callStatus(loadRequest(pallet2, "CK-C2", batch)));

        int created = 0;
        int unprocessable = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                created++;
            } else if (code == 422) {
                unprocessable++;
            } else {
                fail("意外状态码: " + code);
            }
        }
        assertEquals(1, created, "同一批次并发只能装入一个托盘");
        assertEquals(1, unprocessable);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_occupancy WHERE batch_key = ?", Integer.class, batch));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_load WHERE batch_key = ? AND status = 'LOADED'",
                Integer.class, batch));
    }

    @Test
    void concurrentShipAndUnload_consistentFinalStateNoPartialWrites() throws Exception {
        String pallet = "PL-CS-" + unique();
        String batch = "BK-PCS-" + unique();
        createPallet(pallet, 0);
        createBatch(batch);
        load(pallet, "CK-CL-1", batch);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/pallets/" + pallet + "/shipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CS-1\"}")),
                () -> callStatus(post("/api/pallets/" + pallet + "/unloads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CU-1\",\"batchKeys\":[\"" + batch + "\"]}")));

        int ship = results.get(0).get(30, TimeUnit.SECONDS);
        int unload = results.get(1).get(30, TimeUnit.SECONDS);
        String palletStatus = jdbc.queryForObject(
                "SELECT status FROM pallet WHERE pallet_key = ?", String.class, pallet);
        String loadStatus = jdbc.queryForObject(
                "SELECT status FROM pallet_load WHERE pallet_key = ?", String.class, pallet);
        Integer occupancy = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_occupancy WHERE batch_key = ?", Integer.class, batch);
        Integer shipments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_shipment WHERE pallet_key = ?", Integer.class,
                pallet);

        if (ship == 201) {
            // 装运先提交：解除被拒；托盘与装载一致 SHIPPED，占用保留
            assertEquals(409, unload);
            assertEquals("SHIPPED", palletStatus);
            assertEquals("SHIPPED", loadStatus);
            assertEquals(1, occupancy);
            assertEquals(1, shipments);
        } else {
            // 解除先提交：托盘无在托批次，装运 422；状态一致 OPEN/UNLOADED，占用释放
            assertEquals(422, ship);
            assertEquals(201, unload);
            assertEquals("OPEN", palletStatus);
            assertEquals("UNLOADED", loadStatus);
            assertEquals(0, occupancy);
            assertEquals(0, shipments);
        }
    }

    @Test
    void concurrentSameLoadCommand_replaysSameResult() throws Exception {
        String pallet = "PL-CI-" + unique();
        String batch = "BK-PCI-" + unique();
        createPallet(pallet, 0);
        createBatch(batch);

        List<Future<String>> results = runConcurrent(
                () -> callBody(loadRequest(pallet, "CK-CI-1", batch)),
                () -> callBody(loadRequest(pallet, "CK-CI-1", batch)));

        String first = results.get(0).get(30, TimeUnit.SECONDS);
        String second = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(first, second, "同键同参并发重放应返回首次响应");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_load WHERE pallet_key = ?", Integer.class, pallet));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_occupancy WHERE batch_key = ?", Integer.class, batch));
    }

    @Test
    void concurrentLoadDifferentBatches_samePalletBothSucceed() throws Exception {
        String pallet = "PL-CB-" + unique();
        String b1 = "BK-PCB1-" + unique();
        String b2 = "BK-PCB2-" + unique();
        createPallet(pallet, 1);
        createBatch(b1);
        createBatch(b2);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(loadRequest(pallet, "CK-CB-1", b1)),
                () -> callStatus(loadRequest(pallet, "CK-CB-2", b2)));
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "不同批次装载同一托盘应串行成功");
        }
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_load WHERE pallet_key = ? AND status = 'LOADED'",
                Integer.class, pallet));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private MockHttpServletRequestBuilder loadRequest(String pallet, String commandKey,
                                                      String batch) {
        return post("/api/pallets/" + pallet + "/loads")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandKey\":\"" + commandKey + "\",\"items\":[{\"batchKey\":\""
                        + batch + "\",\"category\":\"A\",\"compartmentNo\":1}]}");
    }

    private void createPallet(String palletKey, int dividers) throws Exception {
        mockMvc.perform(post("/api/pallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-P-" + unique() + "\",\"palletKey\":\""
                                + palletKey + "\",\"dividerCount\":" + dividers + "}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated());
    }

    private void createBatch(String batchKey) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCmd(
                                "CK-B-" + unique(), batchKey))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated());
    }

    private void load(String pallet, String commandKey, String batch) throws Exception {
        mockMvc.perform(loadRequest(pallet, commandKey, batch))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated());
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode,
                             String batchNo, Instant producedAt, List<String> requiredTests) {
        CreateCmd(String commandKey, String batchKey) {
            this(commandKey, batchKey, "PROD-1", "LOT-1",
                    Instant.parse("2026-01-02T03:04:05Z"), List.of("t1"));
        }
    }

    private int callStatus(MockHttpServletRequestBuilder request) {
        try {
            return mockMvc.perform(request).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String callBody(MockHttpServletRequestBuilder request) {
        try {
            MvcResult result = mockMvc.perform(request).andReturn();
            return result.getResponse().getStatus() + "|"
                    + result.getResponse().getContentAsString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SafeVarargs
    private <T> List<Future<T>> runConcurrent(Callable<T>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CyclicBarrier barrier = new CyclicBarrier(tasks.length);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return task.call();
            }));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        return futures;
    }
}
