package com.example.starter.water;

import com.example.starter.water.RebalanceService.Evaluation;
import com.example.starter.water.RebalanceService.NormalizedDetail;
import com.example.starter.water.RebalanceService.ViolationKind;
import com.example.starter.water.WaterRepository.BlockQuotaRow;
import com.example.starter.water.WaterRepository.SourceRow;
import com.example.starter.water.dto.Dtos.RebalanceDetailInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重平衡规范化与完整矩阵评估的纯单元测试（不依赖 Spring/数据库）。
 * 覆盖：重复明细合并求和、换序等价、三水源闭环守恒、供给上限、已核销量不可搬走。
 */
class RebalanceMatrixUnitTests {

    private final RebalanceService service =
            new RebalanceService(null, null, new ObjectMapper());

    private SourceRow source(String id, String cap) {
        return new SourceRow(1L, id, new BigDecimal(cap), 1L);
    }

    private BlockQuotaRow cell(long cellId, String block, String sourceId, String quota, String consumed) {
        return new BlockQuotaRow(cellId, 1L, block, sourceId, new BigDecimal(quota),
                new BigDecimal(consumed), 0L, 1L, 1L);
    }

    private Map<String, List<String>> applicability(String block, String... sources) {
        return Map.of(block, List.of(sources));
    }

    private RebalanceDetailInput detail(String block, String from, String to, String volume) {
        return new RebalanceDetailInput(block, from, to, volume);
    }

    // ------------------------------------------------------------------
    // 规范化
    // ------------------------------------------------------------------

    @Test
    void normalizeMergesDuplicateDetailsAndSumsVolumes() {
        List<NormalizedDetail> normalized = service.normalizeDetails(List.of(
                detail("b1", "s1", "s2", "1.500"),
                detail("b1", "s2", "s3", "2"),
                detail("b1", "s1", "s2", "0.250")));
        assertEquals(2, normalized.size());
        // 同区块同源目标求和：1.5 + 0.25 = 1.75，且稳定排序 s1->s2 在 s2->s3 之前
        assertEquals("b1", normalized.get(0).blockId());
        assertEquals("s1", normalized.get(0).sourceSourceId());
        assertEquals("s2", normalized.get(0).targetSourceId());
        assertEquals(0, normalized.get(0).volume().compareTo(new BigDecimal("1.750")));
        assertEquals(0, normalized.get(1).volume().compareTo(new BigDecimal("2")));
    }

    @Test
    void normalizeIsOrderIndependent() {
        List<NormalizedDetail> a = service.normalizeDetails(List.of(
                detail("b1", "s1", "s2", "1"),
                detail("b2", "s2", "s3", "3")));
        List<NormalizedDetail> b = service.normalizeDetails(List.of(
                detail("b2", "s2", "s3", "1.500"),
                detail("b2", "s2", "s3", "1.500"),
                detail("b1", "s1", "s2", "1")));
        // 明细换序、拆分成等价两条不影响规范化语义（数值用 compareTo 忽略尾零）
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).blockId(), b.get(i).blockId());
            assertEquals(a.get(i).sourceSourceId(), b.get(i).sourceSourceId());
            assertEquals(a.get(i).targetSourceId(), b.get(i).targetSourceId());
            assertEquals(0, a.get(i).volume().compareTo(b.get(i).volume()));
        }
    }

    @Test
    void normalizeRejectsInvalidCountsAndVolumes() {
        // 少于 2 条
        assertThrows(ApiException.class, () -> service.normalizeDetails(
                List.of(detail("b1", "s1", "s2", "1"))));
        // 超过 50 条
        List<RebalanceDetailInput> tooMany = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> detail("b" + i, "s1", "s2", "1")).toList();
        assertThrows(ApiException.class, () -> service.normalizeDetails(tooMany));
        // 非正 / 超过三位小数 / 源目标相同
        assertThrows(ApiException.class, () -> service.normalizeDetails(List.of(
                detail("b1", "s1", "s2", "0"), detail("b1", "s1", "s2", "1"))));
        assertThrows(ApiException.class, () -> service.normalizeDetails(List.of(
                detail("b1", "s1", "s2", "1.0001"), detail("b1", "s1", "s2", "1"))));
        assertThrows(ApiException.class, () -> service.normalizeDetails(List.of(
                detail("b1", "s1", "s1", "1"), detail("b1", "s1", "s2", "1"))));
    }

    // ------------------------------------------------------------------
    // 完整矩阵评估
    // ------------------------------------------------------------------

    @Test
    void threeSourceClosedLoopConservesBlockAndSourceTotals() {
        List<SourceRow> sources = List.of(source("s1", "20"), source("s2", "20"), source("s3", "20"));
        // 每个适用水源都有一个单元格（含初始 0 的目标格）：闭环 b1 s1->s2, b2 s2->s3, b3 s3->s1 各 3
        List<BlockQuotaRow> cells = List.of(
                cell(1, "b1", "s1", "6", "0"),
                cell(2, "b1", "s2", "0", "0"),
                cell(3, "b2", "s2", "6", "0"),
                cell(4, "b2", "s3", "0", "0"),
                cell(5, "b3", "s3", "6", "0"),
                cell(6, "b3", "s1", "0", "0"));
        Map<String, List<String>> applicable = Map.of(
                "b1", List.of("s1", "s2"),
                "b2", List.of("s2", "s3"),
                "b3", List.of("s3", "s1"));
        List<NormalizedDetail> details = service.normalizeDetails(List.of(
                detail("b3", "s3", "s1", "3"),
                detail("b1", "s1", "s2", "3"),
                detail("b2", "s2", "s3", "3")));

        Evaluation result = service.evaluate(sources, applicable, cells, details);

        assertTrue(result.valid(), "闭环应合法: " + result.violations());
        assertTrue(result.conserved());
        // 每个水源总分配闭环后不变（各失 3 得 3）
        assertEquals(0, result.totalsAfter().get("s1").compareTo(new BigDecimal("6")));
        assertEquals(0, result.totalsAfter().get("s2").compareTo(new BigDecimal("6")));
        assertEquals(0, result.totalsAfter().get("s3").compareTo(new BigDecimal("6")));
        // 各区块总额度守恒：水在区块内搬到目标水源
        assertEquals(0, quota(result, "b1", "s1").compareTo(new BigDecimal("3")));
        assertEquals(0, quota(result, "b1", "s2").compareTo(new BigDecimal("3")));
        assertEquals(0, quota(result, "b2", "s2").compareTo(new BigDecimal("3")));
        assertEquals(0, quota(result, "b2", "s3").compareTo(new BigDecimal("3")));
        assertEquals(0, quota(result, "b3", "s3").compareTo(new BigDecimal("3")));
        assertEquals(0, quota(result, "b3", "s1").compareTo(new BigDecimal("3")));
    }

    @Test
    void redistributionRespectsSupplyCap() {
        // s1 上限 10、s2 上限 5；s1 已分 6、s2 已分 4。从 s1 搬 2 到 s2 后 s2=6 > 5
        List<SourceRow> sources = List.of(source("s1", "10"), source("s2", "5"));
        List<BlockQuotaRow> cells = List.of(
                cell(1, "b1", "s1", "6", "0"),
                cell(2, "b1", "s2", "4", "0"));
        Map<String, List<String>> applicable = Map.of("b1", List.of("s1", "s2"));
        List<NormalizedDetail> details = service.normalizeDetails(List.of(
                detail("b1", "s1", "s2", "2"),
                detail("b1", "s2", "s1", "0.5")));

        Evaluation result = service.evaluate(sources, applicable, cells, details);

        assertFalse(result.valid());
        assertTrue(result.violations().stream()
                .anyMatch(v -> v.kind() == ViolationKind.SUPPLY_CAP_EXCEEDED));
        // 净搬入 s2 = 2 - 0.5 = 1.5 -> s2 后态 5.5 超限
        assertEquals(0, result.afterQuota()
                .get(new RebalanceService.CellKey("b1", "s2")).compareTo(new BigDecimal("5.5")));
        // 区块总额度仍守恒（10）
        assertTrue(result.conserved());
    }

    @Test
    void consumedWaterCannotBeMovedAway() {
        // s1 额度 5、已核销 4，可搬仅 1；搬走 3 后后态 2 < consumed 4 -> 拒绝
        List<SourceRow> sources = List.of(source("s1", "10"), source("s2", "10"));
        List<BlockQuotaRow> cells = List.of(
                cell(1, "b1", "s1", "5", "4"),
                cell(2, "b1", "s2", "5", "0"));
        Map<String, List<String>> applicable = Map.of("b1", List.of("s1", "s2"));
        List<NormalizedDetail> details = service.normalizeDetails(List.of(
                detail("b1", "s1", "s2", "3"),
                detail("b1", "s2", "s1", "0.5")));

        Evaluation result = service.evaluate(sources, applicable, cells, details);

        assertFalse(result.valid());
        assertTrue(result.violations().stream()
                .anyMatch(v -> v.kind() == ViolationKind.INSUFFICIENT_MOVABLE));
        // 只搬走未核销部分（净 1，使后态恰好等于 consumed 4）则合法
        List<NormalizedDetail> okDetails = service.normalizeDetails(List.of(
                detail("b1", "s1", "s2", "2"),
                detail("b1", "s2", "s1", "1")));
        Evaluation okResult = service.evaluate(sources, applicable, cells, okDetails);
        assertTrue(okResult.valid(), "净搬 1 后 s1 后态 4 == 已核销 4，应合法: " + okResult.violations());
    }

    @Test
    void targetSourceMustBeApplicableToBlock() {
        List<SourceRow> sources = List.of(source("s1", "10"), source("s2", "10"));
        List<BlockQuotaRow> cells = List.of(cell(1, "b1", "s1", "5", "0"));
        Map<String, List<String>> applicable = Map.of("b1", List.of("s1"));
        List<NormalizedDetail> details = service.normalizeDetails(List.of(
                detail("b1", "s1", "s2", "1"),
                detail("b1", "s2", "s1", "1")));

        Evaluation result = service.evaluate(sources, applicable, cells, details);
        assertFalse(result.valid());
        assertTrue(result.violations().stream()
                .anyMatch(v -> v.kind() == ViolationKind.TARGET_NOT_APPLICABLE));
    }

    private BigDecimal quota(Evaluation result, String block, String sourceId) {
        return result.afterQuota().get(new RebalanceService.CellKey(block, sourceId));
    }
}
