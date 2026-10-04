package com.example.starter.batch;

import com.example.starter.batch.dto.BindRecipeRequest;
import com.example.starter.batch.dto.CorrectWeighingRequest;
import com.example.starter.batch.dto.CreateRecipeRequest;
import com.example.starter.batch.dto.RecipeBindingResponse;
import com.example.starter.batch.dto.RecipeDeviation;
import com.example.starter.batch.dto.RecipeResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.WeighRequest;
import com.example.starter.batch.dto.WeighingRecordView;
import com.example.starter.batch.dto.WeighingResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 配方与称量核心服务：配方版本创建、批次绑定、称量累加、更正替代、
 * 放行配方门禁与拆分质量继承。
 *
 * <p>精度约定：质量单位克，称量与目标质量最多 6 位小数，配方百分比最多 4 位小数；
 * 占比偏差判定使用高精度计算，不提前舍入，仅展示值做显示舍入。
 *
 * <p>并发策略：称量/更正/绑定与检验、批准、拆分共用 batch 行锁（SELECT ... FOR UPDATE）
 * 串行化；幂等经 {@link IdempotentExecutor}，身份为操作+操作者+资源+全部参数+期望版本
 * 的结构化 JSON 摘要，避免分隔符碰撞。
 */
@Service
public class RecipeService {

    /** 称量记录类型：普通称量。 */
    public static final String KIND_WEIGH = "WEIGH";
    /** 称量记录类型：更正替代。 */
    public static final String KIND_CORRECTION = "CORRECTION";
    /** 称量记录类型：拆分继承。 */
    public static final String KIND_INHERIT = "INHERIT";

    private static final String CMD_CREATE_RECIPE = "CREATE_RECIPE";
    private static final String CMD_BIND = "BIND_RECIPE";
    private static final String CMD_WEIGH = "WEIGH";
    private static final String CMD_CORRECT = "CORRECT_WEIGHING";

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.1");
    private static final MathContext MC = MathContext.DECIMAL128;

    private final RecipeRepository recipes;
    private final BatchRepository batches;
    private final IdempotentExecutor idempotent;
    private final ObjectMapper objectMapper;

    public RecipeService(RecipeRepository recipes, BatchRepository batches,
                         IdempotentExecutor idempotent, ObjectMapper objectMapper) {
        this.recipes = recipes;
        this.batches = batches;
        this.idempotent = idempotent;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建配方版本：2～6 种成分，百分比正值、最多 4 位小数、合计精确等于 100；创建后不可修改。
     */
    public StoredResponse createRecipe(CreateRecipeRequest req) {
        List<CreateRecipeRequest.IngredientSpec> specs = req.ingredients();
        List<String> names = specs.stream().map(s -> s.name().trim()).toList();
        if (new HashSet<>(names).size() != names.size()) {
            throw ApiException.badRequest("成分名称重复");
        }
        BigDecimal sum = BigDecimal.ZERO;
        List<Map<String, Object>> ingredientParams = new ArrayList<>();
        for (CreateRecipeRequest.IngredientSpec spec : specs) {
            BigDecimal pct = spec.percentage();
            if (pct.signum() <= 0) {
                throw ApiException.badRequest("质量百分比必须为正值: " + spec.name());
            }
            if (pct.scale() > 4) {
                throw ApiException.badRequest("质量百分比最多 4 位小数: " + spec.name());
            }
            sum = sum.add(pct);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", spec.name().trim());
            entry.put("percentage", pct);
            ingredientParams.add(entry);
        }
        if (sum.compareTo(HUNDRED) != 0) {
            throw ApiException.badRequest("质量百分比合计必须精确等于 100，实际为 " + sum);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("recipeKey", req.recipeKey());
        params.put("ingredients", ingredientParams);
        String fingerprint = structuredFingerprint(CMD_CREATE_RECIPE, null, req.recipeKey(), params);
        return idempotent.execute(CMD_CREATE_RECIPE, req.requestId(), fingerprint, () -> {
            recipes.findRecipe(req.recipeKey()).ifPresent(r -> {
                throw ApiException.conflict("recipeKey 已存在: " + req.recipeKey());
            });
            String now = now();
            recipes.insertRecipe(req.recipeKey(), now);
            List<RecipeResponse.IngredientView> views = new ArrayList<>();
            for (int i = 0; i < specs.size(); i++) {
                recipes.insertIngredient(req.recipeKey(), names.get(i),
                        specs.get(i).percentage().toString(), i + 1);
                views.add(new RecipeResponse.IngredientView(names.get(i), specs.get(i).percentage()));
            }
            RecipeResponse body = new RecipeResponse(req.recipeKey(), views, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 批次绑定不可变配方版本及目标总质量（克，最多 6 位小数）；一批仅可绑定一次。
     */
    public StoredResponse bind(String batchKey, String actorId, BindRecipeRequest req) {
        String actor = requireActor(actorId);
        BigDecimal target = req.targetMassGrams();
        if (target.signum() <= 0) {
            throw ApiException.badRequest("目标总质量必须为正值");
        }
        if (target.scale() > 6) {
            throw ApiException.badRequest("目标总质量最多 6 位小数");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("recipeKey", req.recipeKey());
        params.put("targetMassGrams", target);
        String fingerprint = structuredFingerprint(CMD_BIND, actor, batchKey, params);
        return idempotent.execute(CMD_BIND, req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batches.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交，重放首次结果
            var loggedBind = idempotent.loggedResponse(CMD_BIND, req.requestId(), fingerprint);
            if (loggedBind.isPresent()) {
                return loggedBind.get();
            }
            assertNoRecalledAncestor(batchKey);
            String status = batch.status();
            if (!BatchStatus.QUARANTINED.name().equals(status)
                    && !BatchStatus.PENDING_RELEASE.name().equals(status)
                    && !BatchStatus.RELEASE_REVIEW.name().equals(status)) {
                throw ApiException.conflict("批次状态 " + status + " 不允许绑定配方");
            }
            if (recipes.findBinding(batchKey).isPresent()) {
                throw ApiException.conflict("批次已绑定配方: " + batchKey);
            }
            recipes.findRecipe(req.recipeKey())
                    .orElseThrow(() -> ApiException.notFound("配方不存在: " + req.recipeKey()));
            String now = now();
            recipes.insertBinding(new RecipeRepository.BindingRow(0L, batchKey, req.recipeKey(),
                    target.toString(), actor, now));
            RecipeBindingResponse body = new RecipeBindingResponse(batchKey, req.recipeKey(),
                    target, actor, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }
    /**
     * 称量：同成分多次称量累加；负量、未知成分或超精度输入 400；期望版本不一致 409。
     */
    public StoredResponse weigh(String batchKey, String actorId, WeighRequest req) {
        String actor = requireActor(actorId);
        BigDecimal mass = req.massGrams();
        if (mass.signum() <= 0) {
            throw ApiException.badRequest("称量值必须为正值");
        }
        if (mass.scale() > 6) {
            throw ApiException.badRequest("称量值最多 6 位小数");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ingredient", req.ingredient());
        params.put("massGrams", mass);
        params.put("expectedVersion", req.expectedVersion());
        String fingerprint = structuredFingerprint(CMD_WEIGH, actor, batchKey, params);
        return idempotent.execute(CMD_WEIGH, req.requestId(), fingerprint, () -> {
            batches.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var loggedWeigh = idempotent.loggedResponse(CMD_WEIGH, req.requestId(), fingerprint);
            if (loggedWeigh.isPresent()) {
                return loggedWeigh.get();
            }
            assertNoRecalledAncestor(batchKey);
            assertWeighingOpen(batchKey);
            RecipeRepository.BindingRow binding = requireBinding(batchKey);
            List<RecipeRepository.IngredientRow> ingredients = recipes.findIngredients(binding.recipeKey());
            String ingredient = req.ingredient().trim();
            if (ingredients.stream().noneMatch(i -> i.ingredient().equals(ingredient))) {
                throw ApiException.badRequest("未知成分: " + ingredient);
            }
            int version = assertChainVersion(batchKey, req.expectedVersion());
            String now = now();
            long id = recipes.insertWeighing(new RecipeRepository.WeighingRow(0L, batchKey,
                    ingredient, mass.toString(), KIND_WEIGH, null, null, actor, version + 1, now));
            WeighingResponse body = new WeighingResponse(batchKey, id, ingredient, mass,
                    KIND_WEIGH, null, version + 1, validTotals(batchKey, ingredients),
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 称量更正：指定该成分最近有效称量ID，以替代量重算而不是重复累加；
     * 负量、未知成分或超精度输入 400；只能更正最近有效记录，否则 409。
     */
    public StoredResponse correct(String batchKey, String actorId, CorrectWeighingRequest req) {
        String actor = requireActor(actorId);
        BigDecimal mass = req.massGrams();
        if (mass.signum() < 0) {
            throw ApiException.badRequest("更正量不得为负");
        }
        if (mass.scale() > 6) {
            throw ApiException.badRequest("更正量最多 6 位小数");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("replacesWeighingId", req.replacesWeighingId());
        params.put("ingredient", req.ingredient());
        params.put("massGrams", mass);
        params.put("expectedVersion", req.expectedVersion());
        String fingerprint = structuredFingerprint(CMD_CORRECT, actor, batchKey, params);
        return idempotent.execute(CMD_CORRECT, req.requestId(), fingerprint, () -> {
            batches.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var loggedCorrect = idempotent.loggedResponse(CMD_CORRECT, req.requestId(), fingerprint);
            if (loggedCorrect.isPresent()) {
                return loggedCorrect.get();
            }
            assertNoRecalledAncestor(batchKey);
            assertWeighingOpen(batchKey);
            RecipeRepository.BindingRow binding = requireBinding(batchKey);
            List<RecipeRepository.IngredientRow> ingredients = recipes.findIngredients(binding.recipeKey());
            String ingredient = req.ingredient().trim();
            if (ingredients.stream().noneMatch(i -> i.ingredient().equals(ingredient))) {
                throw ApiException.badRequest("未知成分: " + ingredient);
            }
            RecipeRepository.WeighingRow target = recipes.findWeighing(req.replacesWeighingId())
                    .filter(w -> w.batchKey().equals(batchKey))
                    .orElseThrow(() -> ApiException.notFound(
                            "称量记录不存在: " + req.replacesWeighingId()));
            if (!target.ingredient().equals(ingredient)) {
                throw ApiException.conflict("成分与被更正记录不一致: " + ingredient);
            }
            if (target.supersededBy() != null) {
                throw ApiException.conflict("该称量已被更正: " + target.id());
            }
            long latestValid = recipes.findValidWeighings(batchKey).stream()
                    .filter(w -> w.ingredient().equals(ingredient))
                    .mapToLong(RecipeRepository.WeighingRow::id)
                    .max().orElse(-1L);
            if (target.id() != latestValid) {
                throw ApiException.conflict("只能更正该成分最近有效称量: " + target.id());
            }
            int version = assertChainVersion(batchKey, req.expectedVersion());
            String now = now();
            long id = recipes.insertWeighing(new RecipeRepository.WeighingRow(0L, batchKey,
                    ingredient, mass.toString(), KIND_CORRECTION, target.id(), null, actor,
                    version + 1, now));
            recipes.markSuperseded(target.id(), id);
            WeighingResponse body = new WeighingResponse(batchKey, id, ingredient, mass,
                    KIND_CORRECTION, target.id(), version + 1, validTotals(batchKey, ingredients),
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }
    /**
     * 放行配方门禁：批次已绑定配方时，必须每种成分齐全、总质量与目标精确相等，
     * 且每项实际占比偏差不超过 0.1 个百分点（高精度判定，不提前舍入）；
     * 不满足抛 {@link RecipeNotMetException}（422）并携带全部成分差额；未绑定配方不拦截。
     */
    public void assertRecipeSatisfied(String batchKey) {
        Optional<RecipeRepository.BindingRow> bindingOpt = recipes.findBinding(batchKey);
        if (bindingOpt.isEmpty()) {
            return;
        }
        RecipeRepository.BindingRow binding = bindingOpt.get();
        List<RecipeRepository.IngredientRow> ingredients = recipes.findIngredients(binding.recipeKey());
        Map<String, BigDecimal> totals = validTotals(batchKey, ingredients);
        BigDecimal target = new BigDecimal(binding.targetMass());
        Set<String> present = new HashSet<>();
        for (RecipeRepository.WeighingRow row : recipes.findValidWeighings(batchKey)) {
            present.add(row.ingredient());
        }
        BigDecimal totalSum = totals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean met = totalSum.compareTo(target) == 0;
        List<RecipeDeviation> deviations = new ArrayList<>();
        for (RecipeRepository.IngredientRow ingredient : ingredients) {
            BigDecimal pct = new BigDecimal(ingredient.percentage());
            BigDecimal actual = totals.get(ingredient.ingredient());
            BigDecimal expectedMass = pct.multiply(target).divide(HUNDRED, MC);
            BigDecimal actualPct = actual.divide(target, MC).multiply(HUNDRED);
            if (!present.contains(ingredient.ingredient())
                    || actualPct.subtract(pct).abs().compareTo(TOLERANCE) > 0) {
                met = false;
            }
            deviations.add(new RecipeDeviation(ingredient.ingredient(), pct, display(actualPct),
                    display(expectedMass), actual, display(actual.subtract(expectedMass))));
        }
        if (!met) {
            throw new RecipeNotMetException("批次投料不满足配方要求，不允许放行", deviations);
        }
    }

    /**
     * 拆分继承计划：按子批目标质量比例精确分摊父批各成分有效累计量。
     * 父批未绑定配方时子批不得提供目标质量；已绑定时子批质量之和必须等于父批目标质量，
     * 且每个成分分摊量必须能在 6 位小数内精确表示，否则 422。
     */
    public Optional<SplitInheritancePlan> prepareSplitInheritance(
            String parentKey, List<SplitRequest.ChildSpec> children) {
        Optional<RecipeRepository.BindingRow> bindingOpt = recipes.findBinding(parentKey);
        if (bindingOpt.isEmpty()) {
            for (SplitRequest.ChildSpec child : children) {
                if (child.targetMassGrams() != null) {
                    throw ApiException.badRequest("父批未绑定配方，子批不应提供 targetMassGrams");
                }
            }
            return Optional.empty();
        }
        RecipeRepository.BindingRow binding = bindingOpt.get();
        BigDecimal parentTarget = new BigDecimal(binding.targetMass());
        List<RecipeRepository.IngredientRow> ingredients = recipes.findIngredients(binding.recipeKey());
        Map<String, BigDecimal> totals = validTotals(parentKey, ingredients);
        BigDecimal sum = BigDecimal.ZERO;
        for (SplitRequest.ChildSpec child : children) {
            BigDecimal mass = child.targetMassGrams();
            if (mass == null) {
                throw ApiException.badRequest("父批已绑定配方，子批必须提供 targetMassGrams");
            }
            if (mass.signum() <= 0) {
                throw ApiException.badRequest("子批目标质量必须为正值");
            }
            if (mass.scale() > 6) {
                throw ApiException.badRequest("子批目标质量最多 6 位小数");
            }
            sum = sum.add(mass);
        }
        if (sum.compareTo(parentTarget) != 0) {
            throw ApiException.unprocessable("子批目标质量之和 " + sum
                    + " 不等于父批目标质量 " + parentTarget + "，无法守恒");
        }
        List<ChildPlan> plans = new ArrayList<>();
        for (SplitRequest.ChildSpec child : children) {
            List<BigDecimal> shares = new ArrayList<>();
            for (RecipeRepository.IngredientRow ingredient : ingredients) {
                BigDecimal share;
                try {
                    share = totals.get(ingredient.ingredient()).multiply(child.targetMassGrams())
                            .divide(parentTarget, 6, RoundingMode.UNNECESSARY);
                } catch (ArithmeticException e) {
                    throw ApiException.unprocessable("成分 " + ingredient.ingredient()
                            + " 按子批质量比例无法在 6 位小数内精确继承，拆分不守恒");
                }
                shares.add(share);
            }
            plans.add(new ChildPlan(child.batchKey(), child.targetMassGrams(), shares));
        }
        return Optional.of(new SplitInheritancePlan(binding.recipeKey(), plans));
    }

    /**
     * 应用拆分继承：为子批建立相同配方绑定，并按计划写入 INHERIT 称量记录。
     * 在拆分事务内调用，与子批创建、父批状态变更原子提交。
     */
    public void applySplitInheritance(SplitInheritancePlan plan, String now) {
        for (ChildPlan child : plan.children()) {
            recipes.insertBinding(new RecipeRepository.BindingRow(0L, child.batchKey(),
                    plan.recipeKey(), child.targetMass().toString(), "SYSTEM", now));
        }
    }

    /**
     * 为单个子批写入继承称量记录（每个成分一条 INHERIT，链版本从 1 递增）。
     */
    public void applyChildInheritance(SplitInheritancePlan plan, String childKey, String now) {
        ChildPlan child = plan.children().stream()
                .filter(c -> c.batchKey().equals(childKey)).findFirst()
                .orElseThrow(() -> new IllegalStateException("拆分继承计划缺少子批: " + childKey));
        List<RecipeRepository.IngredientRow> ingredients = recipes.findIngredients(plan.recipeKey());
        int version = 0;
        for (int i = 0; i < ingredients.size(); i++) {
            version++;
            recipes.insertWeighing(new RecipeRepository.WeighingRow(0L, childKey,
                    ingredients.get(i).ingredient(), child.shares().get(i).toString(),
                    KIND_INHERIT, null, null, "SYSTEM", version, now));
        }
    }

    /**
     * 拆分继承计划：配方键 + 各子批目标质量与按配方顺序的成分分摊量。
     */
    public record SplitInheritancePlan(String recipeKey, List<ChildPlan> children) {
    }

    /**
     * 单个子批的继承计划。
     */
    public record ChildPlan(String batchKey, BigDecimal targetMass, List<BigDecimal> shares) {
    }

    /**
     * 批次配方绑定历史视图；未绑定时为空。
     */
    public Optional<RecipeBindingResponse> bindingView(String batchKey) {
        return recipes.findBinding(batchKey)
                .map(b -> new RecipeBindingResponse(b.batchKey(), b.recipeKey(),
                        new BigDecimal(b.targetMass()), b.boundBy(), Instant.parse(b.createdAt())));
    }

    /**
     * 批次全部称量记录历史视图（含已作废），按称量ID升序。
     */
    public List<WeighingRecordView> weighingViews(String batchKey) {
        return recipes.findWeighings(batchKey).stream()
                .map(w -> new WeighingRecordView(w.id(), w.ingredient(), new BigDecimal(w.mass()),
                        w.kind(), w.replacesId(), w.supersededBy(), w.chainVersion(), w.actorId(),
                        Instant.parse(w.createdAt())))
                .toList();
    }
    /**
     * 有效称量累计：按配方成分顺序返回各成分有效（未作废）记录的质量合计，无记录为 0。
     */
    private Map<String, BigDecimal> validTotals(String batchKey,
                                                List<RecipeRepository.IngredientRow> ingredients) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (RecipeRepository.IngredientRow ingredient : ingredients) {
            totals.put(ingredient.ingredient(), BigDecimal.ZERO);
        }
        for (RecipeRepository.WeighingRow row : recipes.findValidWeighings(batchKey)) {
            totals.computeIfPresent(row.ingredient(),
                    (k, v) -> v.add(new BigDecimal(row.mass())));
        }
        return totals;
    }

    /**
     * 批次必须已绑定配方，否则 422。
     */
    private RecipeRepository.BindingRow requireBinding(String batchKey) {
        return recipes.findBinding(batchKey)
                .orElseThrow(() -> ApiException.unprocessable("批次未绑定配方: " + batchKey));
    }

    /**
     * 称量链开放状态校验：放行后冻结、已拆分父批不可再更正、终态不可称量。
     */
    private void assertWeighingOpen(String batchKey) {
        BatchRepository.BatchRow batch = batches.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        String status = batch.status();
        if (BatchStatus.RELEASED.name().equals(status)) {
            throw ApiException.conflict("批次已放行，称量链已冻结: " + batchKey);
        }
        if (BatchStatus.SPLIT.name().equals(status)) {
            throw ApiException.conflict("批次已拆分，不能再称量或更正: " + batchKey);
        }
        if (!BatchStatus.QUARANTINED.name().equals(status)
                && !BatchStatus.PENDING_RELEASE.name().equals(status)
                && !BatchStatus.RELEASE_REVIEW.name().equals(status)) {
            throw ApiException.conflict("批次状态 " + status + " 不允许称量或更正");
        }
    }

    /**
     * 期望版本校验：与当前称量链版本不一致返回 409，返回当前版本。
     */
    private int assertChainVersion(String batchKey, int expectedVersion) {
        int version = recipes.countWeighings(batchKey);
        if (version != expectedVersion) {
            throw ApiException.conflict("称量链版本不一致，期望 " + expectedVersion
                    + "，实际 " + version);
        }
        return version;
    }

    /**
     * 若任一级祖先已被召回则抛 422：禁止新增称量与更正。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        Map<String, String> parentOf = new LinkedHashMap<>();
        for (BatchRepository.LineageRow row : batches.findAllLineage()) {
            parentOf.put(row.childKey(), row.parentKey());
        }
        Set<String> recalled = new HashSet<>(batches.findRecalledKeys());
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            if (recalled.contains(current)) {
                throw ApiException.unprocessable(
                        "祖先批次 " + current + " 已召回，禁止称量、更正与绑定");
            }
        }
    }

    private String requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        return actorId.trim();
    }

    /**
     * 展示舍入：仅用于偏差明细输出，判定不使用该值。
     */
    private static BigDecimal display(BigDecimal value) {
        return new BigDecimal(value.setScale(8, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString());
    }

    /**
     * 结构化指纹：操作+操作者+资源+全部参数（含期望版本）的确定性 JSON 取 SHA-256，
     * 结构化比较避免分隔符碰撞。
     */
    private String structuredFingerprint(String operation, String actor, String resource,
                                         Map<String, ?> params) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", operation);
        identity.put("actor", actor);
        identity.put("resource", resource);
        identity.put("params", params);
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(identity);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private String now() {
        return Instant.now().toString();
    }
}