package com.example.coral;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.coral.domain.PlantStatus;
import com.example.coral.domain.QuadratStatus;
import com.example.coral.service.BusinessException;
import com.example.coral.service.QuadratService;
import com.example.coral.service.TransplantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 复核链、死亡释放容量与封存规则：真实H2验证状态迁移与快照一致性。
 */
@SpringBootTest
class ReviewSealTest {

    @Autowired
    private TransplantService transplantService;
    @Autowired
    private QuadratService quadratService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM seal_snapshot");
        jdbc.update("DELETE FROM transplant");
        jdbc.update("DELETE FROM quadrat");
    }

    @Test
    void reviewChainAndDeadCannotRevive() {
        quadratService.create("Q-RV", "复核样方");
        var t = transplantService.register("req-rv-t", "P-RV-1", "苗圃A", "Q-RV");
        transplantService.review("req-rv-1", "P-RV-1", PlantStatus.ALIVE, "第一次复核");
        transplantService.review("req-rv-2", "P-RV-1", PlantStatus.PENDING, "存疑待复核");
        transplantService.review("req-rv-3", "P-RV-1", PlantStatus.DEAD, "确认死亡");

        var chain = transplantService.reviewChain(t.id());
        assertEquals(3, chain.size());
        assertEquals(PlantStatus.ALIVE, chain.get(0).result());
        assertEquals(PlantStatus.PENDING, chain.get(1).result());
        assertEquals(PlantStatus.DEAD, chain.get(2).result());
        assertEquals(PlantStatus.DEAD, transplantService.findByPlantCode("P-RV-1").status());

        // 死亡后再标存活或待复核均拒绝
        BusinessException e = assertThrows(BusinessException.class, () ->
                transplantService.review("req-rv-4", "P-RV-1", PlantStatus.ALIVE, "复活"));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertThrows(BusinessException.class, () ->
                transplantService.review("req-rv-5", "P-RV-1", PlantStatus.PENDING, null));

        // 死亡记录不可删：移植记录仍在
        assertEquals(1, quadratService.plantsOf(
                quadratService.findByCode("Q-RV").id()).size());
    }

    @Test
    void reviewIdempotentReplayAndParamConflict() {
        quadratService.create("Q-RI", "复核幂等样方");
        transplantService.register("req-ri-t", "P-RI-1", "苗圃A", "Q-RI");
        var first = transplantService.review("req-ri-1", "P-RI-1", PlantStatus.ALIVE, "ok");

        var replay = transplantService.review("req-ri-1", "P-RI-1", PlantStatus.ALIVE, "ok");
        assertEquals(first.id(), replay.id());

        BusinessException e = assertThrows(BusinessException.class, () ->
                transplantService.review("req-ri-1", "P-RI-1", PlantStatus.DEAD, "ok"));
        assertEquals(HttpStatus.CONFLICT, e.status());
    }

    @Test
    void deathReleasesCapacityForNewTransplant() {
        quadratService.create("Q-REL", "释放样方");
        for (int i = 1; i <= 12; i++) {
            transplantService.register("req-rel-t" + i, "P-REL-" + i, "苗圃A", "Q-REL");
        }
        transplantService.review("req-rel-kill", "P-REL-1", PlantStatus.DEAD, "死亡");

        // 死亡释放容量后可登记新植株
        transplantService.register("req-rel-new", "P-REL-NEW", "苗圃B", "Q-REL");
        var q = quadratService.findByCode("Q-REL");
        assertEquals(13, quadratService.plantsOf(q.id()).size());

        // 再次超出12株活跃仍被拒绝
        BusinessException e = assertThrows(BusinessException.class, () ->
                transplantService.register("req-rel-x", "P-REL-X", "苗圃B", "Q-REL"));
        assertEquals(HttpStatus.CONFLICT, e.status());
    }

    @Test
    void sealRequiresNoPendingAndSnapshotKeepsDead() {
        quadratService.create("Q-SEAL", "封存样方");
        transplantService.register("req-s-1", "P-S-1", "苗圃A", "Q-SEAL");
        transplantService.register("req-s-2", "P-S-2", "苗圃A", "Q-SEAL");
        transplantService.register("req-s-3", "P-S-3", "苗圃B", "Q-SEAL");
        transplantService.review("req-s-r1", "P-S-1", PlantStatus.ALIVE, null);
        transplantService.review("req-s-r2", "P-S-2", PlantStatus.DEAD, "白化死亡");

        // 存在待复核植株时禁止封存
        BusinessException e = assertThrows(BusinessException.class, () ->
                quadratService.seal("Q-SEAL"));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertTrue(e.getMessage().contains("待复核"));

        transplantService.review("req-s-r3", "P-S-3", PlantStatus.ALIVE, null);
        var snapshot = quadratService.seal("Q-SEAL");
        assertEquals(2, snapshot.aliveCount());
        assertEquals(1, snapshot.deadCount());
        assertEquals(0, snapshot.pendingCount());
        // 封存快照保留死亡记录
        assertTrue(snapshot.plantCodes().contains("P-S-2"));
        assertEquals(3, snapshot.plantCodes().size());
        assertEquals(QuadratStatus.SEALED, quadratService.findByCode("Q-SEAL").status());

        // 重放封存返回同一清单版本
        var replay = quadratService.seal("Q-SEAL");
        assertEquals(snapshot.id(), replay.id());

        // 已封存样方禁止新增移植与复核
        assertEquals(HttpStatus.CONFLICT, assertThrows(BusinessException.class, () ->
                transplantService.register("req-s-4", "P-S-4", "苗圃A", "Q-SEAL")).status());
        assertEquals(HttpStatus.CONFLICT, assertThrows(BusinessException.class, () ->
                transplantService.review("req-s-r4", "P-S-1", PlantStatus.DEAD, null)).status());
    }

    @Test
    void failedReviewDoesNotChangeStats() {
        quadratService.create("Q-TX", "事务样方");
        transplantService.register("req-tx-1", "P-TX-1", "苗圃A", "Q-TX");
        transplantService.review("req-tx-r1", "P-TX-1", PlantStatus.DEAD, null);
        // 失败复核（死亡后标活）不得部分改变统计
        assertThrows(BusinessException.class, () ->
                transplantService.review("req-tx-r2", "P-TX-1", PlantStatus.ALIVE, null));
        assertEquals(PlantStatus.DEAD, transplantService.findByPlantCode("P-TX-1").status());
        assertEquals(1, transplantService.reviewChain(
                transplantService.findByPlantCode("P-TX-1").id()).size());
    }
}