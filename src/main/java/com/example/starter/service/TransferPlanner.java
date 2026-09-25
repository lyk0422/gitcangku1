package com.example.starter.service;

import com.example.starter.api.dto.TransferItemRequest;
import com.example.starter.domain.BucketSlot;
import com.example.starter.domain.Buckets;
import com.example.starter.repo.PlanSlotPo;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.ZonePo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 容量转配的纯计算引擎：按完整后态统一计算各航线穿越序列与全量桶占用，
 * 不依赖数据库与 Spring，可独立单元测试。
 *
 * <p>关键语义：</p>
 * <ul>
 *   <li>转配项先按 (routeId, 源桶, 目标桶) 多重集规范化，项顺序不影响后态；</li>
 *   <li>允许多条航线形成 A→B→C→A 闭环：先构造完整后态再统计容量，
 *       绝不逐项释放/占用，避免顺序导致的误判；</li>
 *   <li>目标桶必须与相邻路径（前后槽位的 8 邻接单元与相邻 15 分钟桶）连续；</li>
 *   <li>后态序列占用的网格单元不得与任何有效禁飞区相交（边界接触也算冲突）；</li>
 *   <li>容量按全量占用校验：未参与航线的占用也必须计入。</li>
 * </ul>
 */
public final class TransferPlanner {

    /** 违规类型（与 API ViolationDto.type 对应）。 */
    public static final String V_DISCONTINUOUS_PATH = "DISCONTINUOUS_PATH";
    public static final String V_NO_FLY_CONFLICT = "NO_FLY_CONFLICT";
    public static final String V_ITEM_NOT_IN_PLAN = "ITEM_NOT_IN_PLAN";
    public static final String V_CAPACITY_EXCEEDED = "CAPACITY_EXCEEDED";

    private TransferPlanner() {
    }

    /** 一条规范化转配指令（源桶 → 目标桶）。 */
    public record Directive(String routeId, int routeVersion, int expectedVersion,
                            BucketSlot source, BucketSlot target) {
    }

    /** 一条违规明细。 */
    public record Violation(String type, String routeId, String detail) {
    }

    /** 引擎计算结果。 */
    public static final class Outcome {
        public final Map<String, List<PlanSlotPo>> afterPlans = new TreeMap<>();
        public final List<Violation> violations = new ArrayList<>();
        public final Map<BucketSlot, Integer> usedBefore = new TreeMap<>();
        public final Map<BucketSlot, Integer> usedAfter = new TreeMap<>();
        public final List<BucketSlot> affectedBuckets = new ArrayList<>();

        public boolean feasible() {
            return violations.isEmpty();
        }
    }

    /**
     * 将请求项规范化为按 routeId/源桶/目标桶稳定排序的指令多重集（项顺序不影响语义）。
     * 同一路线重复同一 (源, 目标) 指令表示替换多个同桶槽位。
     */
    public static List<Directive> normalizeDirectives(List<TransferItemRequest> items) {
        List<Directive> directives = new ArrayList<>(items.size());
        for (TransferItemRequest item : items) {
            directives.add(new Directive(item.routeId(), item.routeVersion(), item.expectedVersion(),
                    new BucketSlot(item.source().cellX(), item.source().cellY(),
                            item.source().bucketStart()),
                    new BucketSlot(item.target().cellX(), item.target().cellY(),
                            item.target().bucketStart())));
        }
        // 排序键包含全部字段，保证完全确定；同键并列保持稳定顺序，不影响多重集语义
        directives.sort(Comparator.comparing(Directive::routeId)
                .thenComparing(Directive::routeVersion)
                .thenComparing(Directive::expectedVersion)
                .thenComparing(Directive::source)
                .thenComparing(Directive::target));
        return directives;
    }

    /**
     * 按完整后态计算转配结果。
     *
     * @param routes          参与航线当前状态（routeId → 当前航线，含点列）
     * @param beforePlans     参与航线当前版本穿越序列（routeId → 序列）
     * @param directives      规范化后的转配指令
     * @param baselineUsed    相关桶的转配前全量占用（含未参与航线），未给出按 0
     * @param capacityByBucket 相关桶容量上限，未给出按 0（未配置即不得占用）
     * @param activeZones     全部有效禁飞区
     */
    public static Outcome plan(Map<String, RoutePo> routes,
                               Map<String, List<PlanSlotPo>> beforePlans,
                               List<Directive> directives,
                               Map<BucketSlot, Integer> baselineUsed,
                               Map<BucketSlot, Integer> capacityByBucket,
                               List<ZonePo> activeZones) {
        Outcome outcome = new Outcome();

        // 每航线的待消费指令（已排序），按槽位顺序匹配源桶，匹配不上即集合遗漏
        Map<String, List<Directive>> pendingByRoute = new HashMap<>();
        for (Directive directive : directives) {
            pendingByRoute.computeIfAbsent(directive.routeId(), k -> new ArrayList<>()).add(directive);
        }

        for (Map.Entry<String, List<PlanSlotPo>> entry : beforePlans.entrySet()) {
            String routeId = entry.getKey();
            List<PlanSlotPo> before = entry.getValue();
            List<Directive> pending = new ArrayList<>(
                    pendingByRoute.getOrDefault(routeId, List.of()));
            List<PlanSlotPo> after = new ArrayList<>(before.size());
            for (PlanSlotPo slot : before) {
                BucketSlot current = new BucketSlot(slot.cellX(), slot.cellY(), slot.bucketStart());
                Directive matched = null;
                for (int i = 0; i < pending.size(); i++) {
                    if (pending.get(i).source().equals(current)) {
                        matched = pending.remove(i);
                        break;
                    }
                }
                if (matched != null) {
                    after.add(new PlanSlotPo(slot.seq(), matched.target().cellX(),
                            matched.target().cellY(), matched.target().bucketStart()));
                } else {
                    after.add(slot);
                }
            }
            for (Directive leftover : pending) {
                outcome.violations.add(new Violation(V_ITEM_NOT_IN_PLAN, routeId,
                        "转配源桶不在航线 " + routeId + " 当前穿越序列中: "
                                + bucketText(leftover.source())));
            }
            outcome.afterPlans.put(routeId, after);
        }
        // 参与航线完全没有前态序列（未登记）：其全部指令都属于集合遗漏
        for (Map.Entry<String, List<Directive>> entry : pendingByRoute.entrySet()) {
            if (!beforePlans.containsKey(entry.getKey())) {
                for (Directive leftover : entry.getValue()) {
                    outcome.violations.add(new Violation(V_ITEM_NOT_IN_PLAN, entry.getKey(),
                            "转配源桶不在航线 " + entry.getKey() + " 当前穿越序列中: "
                                    + bucketText(leftover.source())));
                }
            }
        }

        // 后态路径连续性：相邻槽位的网格单元 8 邻接且时间桶相同或相邻
        for (Map.Entry<String, List<PlanSlotPo>> entry : outcome.afterPlans.entrySet()) {
            String routeId = entry.getKey();
            List<PlanSlotPo> after = entry.getValue();
            for (int i = 1; i < after.size(); i++) {
                PlanSlotPo prev = after.get(i - 1);
                PlanSlotPo cur = after.get(i);
                boolean adjacentCells = Buckets.cellsAdjacent(
                        prev.cellX(), prev.cellY(), cur.cellX(), cur.cellY());
                boolean adjacentTime = Buckets.bucketsAdjacent(prev.bucketStart(), cur.bucketStart());
                if (!adjacentCells || !adjacentTime) {
                    outcome.violations.add(new Violation(V_DISCONTINUOUS_PATH, routeId,
                            "航线 " + routeId + " 槽位 " + prev.seq() + "→" + cur.seq()
                                    + " 路径不连续: " + slotText(prev) + " → " + slotText(cur)));
                }
            }
        }

        // 后态禁飞冲突：后态序列占用的网格单元闭矩形与任一有效禁飞闭矩形相交即冲突
        for (Map.Entry<String, List<PlanSlotPo>> entry : outcome.afterPlans.entrySet()) {
            String routeId = entry.getKey();
            for (PlanSlotPo slot : entry.getValue()) {
                int cellXMin = slot.cellX() * Buckets.CELL_SIZE_METERS;
                int cellYMin = slot.cellY() * Buckets.CELL_SIZE_METERS;
                int cellXMax = cellXMin + Buckets.CELL_SIZE_METERS;
                int cellYMax = cellYMin + Buckets.CELL_SIZE_METERS;
                for (ZonePo zone : activeZones) {
                    boolean disjoint = cellXMax < zone.xMin() || cellXMin > zone.xMax()
                            || cellYMax < zone.yMin() || cellYMin > zone.yMax();
                    if (!disjoint) {
                        outcome.violations.add(new Violation(V_NO_FLY_CONFLICT, routeId,
                                "航线 " + routeId + " 转配后槽位 " + slot.seq()
                                        + " 穿越禁飞区 " + zone.zoneId()));
                    }
                }
            }
        }

        // 相关桶集合：参与航线转配前后出现的全部桶
        TreeMap<BucketSlot, Boolean> affected = new TreeMap<>();
        for (List<PlanSlotPo> before : beforePlans.values()) {
            for (PlanSlotPo slot : before) {
                affected.put(new BucketSlot(slot.cellX(), slot.cellY(), slot.bucketStart()),
                        Boolean.TRUE);
            }
        }
        for (List<PlanSlotPo> after : outcome.afterPlans.values()) {
            for (PlanSlotPo slot : after) {
                affected.put(new BucketSlot(slot.cellX(), slot.cellY(), slot.bucketStart()),
                        Boolean.TRUE);
            }
        }
        outcome.affectedBuckets.addAll(affected.keySet());

        // 全量占用前后态：容量按“航班数”计——同一航线在同一桶的多个槽位只算 1 架次。
        // 以数据库给出的全量基线（已按航线去重）为基础，扣除参与航线前态、加入后态。
        Map<BucketSlot, java.util.Set<String>> beforeRoutes = new HashMap<>();
        Map<BucketSlot, java.util.Set<String>> afterRoutes = new HashMap<>();
        for (BucketSlot bucket : outcome.affectedBuckets) {
            beforeRoutes.put(bucket, new java.util.HashSet<>());
            afterRoutes.put(bucket, new java.util.HashSet<>());
            outcome.usedBefore.put(bucket, baselineUsed.getOrDefault(bucket, 0));
            outcome.usedAfter.put(bucket, baselineUsed.getOrDefault(bucket, 0));
        }
        for (Map.Entry<String, List<PlanSlotPo>> entry : beforePlans.entrySet()) {
            for (PlanSlotPo slot : entry.getValue()) {
                beforeRoutes.get(new BucketSlot(slot.cellX(), slot.cellY(), slot.bucketStart()))
                        .add(entry.getKey());
            }
        }
        for (Map.Entry<String, List<PlanSlotPo>> entry : outcome.afterPlans.entrySet()) {
            for (PlanSlotPo slot : entry.getValue()) {
                afterRoutes.get(new BucketSlot(slot.cellX(), slot.cellY(), slot.bucketStart()))
                        .add(entry.getKey());
            }
        }
        for (BucketSlot bucket : outcome.affectedBuckets) {
            int base = baselineUsed.getOrDefault(bucket, 0);
            int afterUsed = base - beforeRoutes.get(bucket).size() + afterRoutes.get(bucket).size();
            outcome.usedAfter.put(bucket, afterUsed);
        }

        // 容量校验（按完整后态，一次判定，不受逐项顺序影响）
        for (BucketSlot bucket : outcome.affectedBuckets) {
            int used = outcome.usedAfter.get(bucket);
            int max = capacityByBucket.getOrDefault(bucket, 0);
            if (used > max) {
                outcome.violations.add(new Violation(V_CAPACITY_EXCEEDED, null,
                        "时空桶 " + bucketText(bucket) + " 转配后占用 " + used
                                + " 超过容量上限 " + max));
            }
        }
        return outcome;
    }

    private static String slotText(PlanSlotPo slot) {
        return "cell(" + slot.cellX() + "," + slot.cellY() + ")@" + slot.bucketStart();
    }

    private static String bucketText(BucketSlot bucket) {
        return "cell(" + bucket.cellX() + "," + bucket.cellY() + ")@" + bucket.bucketStart();
    }
}
