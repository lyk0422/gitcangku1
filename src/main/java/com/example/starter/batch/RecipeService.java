package com.example.starter.batch;

import com.example.starter.batch.dto.BindRecipeRequest;
import com.example.starter.batch.dto.BindRecipeResponse;
import com.example.starter.batch.dto.CorrectWeighingRequest;
import com.example.starter.batch.dto.CreateRecipeRequest;
import com.example.starter.batch.dto.RecipeResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.WeighRequest;
import com.example.starter.batch.dto.WeighingResponse;
import com.example.starter.batch.dto.WeighingStatusResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 配方称量核心服务：不可变配方版本、批次配方绑定、称量与更正、
 * 放行配方门禁与拆分质量守恒继承。
 *
 * <p>并发策略：与批次服务一致，同一批次的称量/更正在事务内对 batch 行
 * SELECT ... FOR UPDATE 串行化，与拆分、批准互斥；requestId 幂等通过
 * command_log 主键 + 结构化参数指纹（操作、操作者、资源、全部参数与期望版本
 * 组成的有序映射序列化后取 SHA-256，避免分隔符拼接碰撞）实现，
 * 同键同参重放返回首次响应快照，同键改参返回 409；仅成功命令写入 command_log，
 * 业务与去重在同一事务原子提交。
 */
@Service
public class RecipeService {

    private static final String CMD_CREATE_RECIPE = "CREATE_RECIPE";
    private static final String CMD_BIND = "BIND_RECIPE";
    private static final String CMD_WEIGH = "WEIGH";
    private static final String CMD_CORRECT = "CORRECT_WEIGHING";

    private static final int MAX_MASS_SCALE = 6;
    private static final int MAX_PERCENTAGE_SCALE = 4;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TOLERANCE_POINTS = new BigDecimal("0.1");
    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final RecipeRepository recipes;
    private final BatchRepository batches;
    private final LineageGuard lineageGuard;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public RecipeService(RecipeRepository recipes,
                         BatchRepository batches,
                         LineageGuard lineageGuard,
                         PlatformTransactionManager transactionManager,
                         ObjectMapper objectMapper) {
        this.recipes = recipes;
        this.batches = batches;
        this.lineageGuard = lineageGuard;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 创建不可变配方版本：2～6 种成分，每种百分比为正、最多 4 位小数，合计精确等于 100。
     */
    public StoredResponse createRecipe(String operator, CreateRecipeRequest req) {
        String op = requireOperator(operator);
        List<CreateRecipeRequest.IngredientSpec> specs = req.ingredients();
        List<String> names = specs.stream().map(s -> s.name().trim()).toList();
        if (new HashSet<>(names).size() != names.size()) {
            throw ApiException.badRequest("ingredients 存在重复成分");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (CreateRecipeRequest.IngredientSpec spec : specs) {
            validatePercentage(spec.percentage());
            sum = sum.add(spec.percentage());
        }
        if (sum.compareTo(HUNDRED) != 0) {
            throw ApiException.badRequest("成分百分比合计必须精确等于 100，当前为 " + sum.toPlainString());
        }
        Map<String, Object> fp = baseFingerprint(CMD_CREATE_RECIPE, op, req.recipeKey());
        fp.put("ingredients", specs.stream()
                .map(s -> List.of(s.name().trim(), s.percentage().toPlainString())).toList());
        return executeIdempotent(CMD_CREATE_RECIPE, req.requestId(), structuredFingerprint(fp), () -> {
            if (recipes.findRecipe(req.recipeKey()).isPresent()) {
                throw ApiException.conflict("recipeKey 已存在: " + req.recipeKey());
            }
            String now = now();
            recipes.insertRecipe(req.recipeKey(), now);
            List<RecipeResponse.Ingredient> ingredients = new ArrayList<>(specs.size());
            for (int i = 0; i < specs.size(); i++) {
                recipes.insertIngredient(req.recipeKey(), names.get(i),
                        specs.get(i).percentage(), i + 1);
                ingredients.add(new RecipeResponse.Ingredient(names.get(i), specs.get(i).percentage()));
            }
            return new StoredResponse(201, toJson(new RecipeResponse(req.recipeKey(), ingredients,
                    Instant.parse(now))));
        });
    }

    /**
     * 查询配方版本。
     */
    public RecipeResponse getRecipe(String recipeKey) {
        recipes.findRecipe(recipeKey)
                .orElseThrow(() -> ApiException.notFound("配方版本不存在: " + recipeKey));
        List<RecipeResponse.Ingredient> ingredients = recipes.findIngredients(recipeKey).stream()
                .map(i -> new RecipeResponse.Ingredient(i.ingredient(), i.percentage()))
                .toList();
        String createdAt = recipes.findRecipe(recipeKey).get().createdAt();
        return new RecipeResponse(recipeKey, ingredients, Instant.parse(createdAt));
    }

    /**
     * 批次绑定不可变配方版本与目标总质量（克，最多 6 位小数）；每批次至多绑定一次。
     */
    public StoredResponse bindRecipe(String operator, String batchKey, BindRecipeRequest req) {
        String op = requireOperator(operator);
        validateMass(req.targetMass(), "targetMass");
        Map<String, Object> fp = baseFingerprint(CMD_BIND, op, batchKey);
        fp.put("recipeKey", req.recipeKey());
        fp.put("targetMass", req.targetMass().toPlainString());
        return executeIdempotent(CMD_BIND, req.requestId(), structuredFingerprint(fp), () -> {
            batches.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 行锁后重查命令快照：锁等待期间同键请求可能已由对方提交，须重放首次结果
            var loggedBind = loggedResponse(CMD_BIND, req.requestId(), structuredFingerprint(fp));
            if (loggedBind.isPresent()) {
                return loggedBind.get();
            }
            recipes.findRecipe(req.recipeKey())
                    .orElseThrow(() -> ApiException.notFound("配方版本不存在: " + req.recipeKey()));
            if (recipes.findBinding(batchKey).isPresent()) {
                throw ApiException.conflict("批次已绑定配方，绑定不可修改: " + batchKey);
            }
            String now = now();
            recipes.insertBinding(batchKey, req.recipeKey(), req.targetMass(), now);
            return new StoredResponse(201, toJson(new BindRecipeResponse(batchKey, req.recipeKey(),
                    req.targetMass(), 0L, Instant.parse(now))));
        });
    }
    /**
     * 新增称量：同成分多次称量累加；负量、未知成分或超精度输入返回 400。
     */
    public StoredResponse weigh(String operator, String batchKey, WeighRequest req) {
        String op = requireOperator(operator);
        validateMass(req.mass(), "mass");
        Map<String, Object> fp = baseFingerprint(CMD_WEIGH, op, batchKey);
        fp.put("ingredient", req.ingredient());
        fp.put("mass", req.mass().toPlainString());
        fp.put("expectedVersion", req.expectedVersion().toString());
        return executeIdempotent(CMD_WEIGH, req.requestId(), structuredFingerprint(fp), () -> {
            BatchRepository.BatchRow batch = lockWeighableBatch(batchKey);
            // 行锁后重查命令快照：锁等待期间同键请求可能已由对方提交，须重放首次结果
            var loggedWeigh = loggedResponse(CMD_WEIGH, req.requestId(), structuredFingerprint(fp));
            if (loggedWeigh.isPresent()) {
                return loggedWeigh.get();
            }
            RecipeRepository.BindingRow binding = recipes.findBinding(batchKey)
                    .orElseThrow(() -> ApiException.unprocessable("批次未绑定配方，不能称量: " + batchKey));
            assertVersion(batchKey, req.expectedVersion());
            boolean known = recipes.findIngredients(binding.recipeKey()).stream()
                    .anyMatch(i -> i.ingredient().equals(req.ingredient()));
            if (!known) {
                throw ApiException.badRequest("成分不属于绑定配方: " + req.ingredient());
            }
            String now = now();
            long id = recipes.insertWeighing(new RecipeRepository.WeighingRow(0L, batchKey,
                    req.requestId(), req.ingredient(), req.mass(), null, null, "MANUAL", op, now));
            long version = recipes.countWeighings(batchKey);
            return new StoredResponse(201, toJson(new WeighingResponse(id, batchKey, req.ingredient(),
                    req.mass(), null, version, Instant.parse(now))));
        });
    }

    /**
     * 称量更正：指定该成分最近一条有效称量，以替代量重算而不是重复累加。
     */
    public StoredResponse correct(String operator, String batchKey, CorrectWeighingRequest req) {
        String op = requireOperator(operator);
        validateMass(req.mass(), "mass");
        Map<String, Object> fp = baseFingerprint(CMD_CORRECT, op, batchKey);
        fp.put("correctsWeighingId", req.correctsWeighingId().toString());
        fp.put("mass", req.mass().toPlainString());
        fp.put("expectedVersion", req.expectedVersion().toString());
        return executeIdempotent(CMD_CORRECT, req.requestId(), structuredFingerprint(fp), () -> {
            lockWeighableBatch(batchKey);
            // 行锁后重查命令快照：锁等待期间同键请求可能已由对方提交，须重放首次结果
            var loggedCorrect = loggedResponse(CMD_CORRECT, req.requestId(),
                    structuredFingerprint(fp));
            if (loggedCorrect.isPresent()) {
                return loggedCorrect.get();
            }
            recipes.findBinding(batchKey)
                    .orElseThrow(() -> ApiException.unprocessable("批次未绑定配方，不能更正称量: " + batchKey));
            assertVersion(batchKey, req.expectedVersion());
            RecipeRepository.WeighingRow target = recipes.findWeighing(req.correctsWeighingId())
                    .filter(w -> w.batchKey().equals(batchKey))
                    .orElseThrow(() -> ApiException.notFound(
                            "称量记录不存在: " + req.correctsWeighingId()));
            if (target.supersededBy() != null) {
                throw ApiException.conflict("称量已被更正替代，不能再次更正: " + target.id());
            }
            List<RecipeRepository.WeighingRow> effectiveForIngredient = recipes
                    .findEffectiveWeighings(batchKey).stream()
                    .filter(w -> w.ingredient().equals(target.ingredient()))
                    .toList();
            long latestId = effectiveForIngredient.get(effectiveForIngredient.size() - 1).id();
            if (latestId != target.id()) {
                throw ApiException.conflict("只能更正该成分最近一条有效称量，最近有效称量ID: " + latestId);
            }
            String now = now();
            long id = recipes.insertWeighing(new RecipeRepository.WeighingRow(0L, batchKey,
                    req.requestId(), target.ingredient(), req.mass(), target.id(), null,
                    "MANUAL", op, now));
            recipes.markSuperseded(target.id(), id);
            long version = recipes.countWeighings(batchKey);
            return new StoredResponse(201, toJson(new WeighingResponse(id, batchKey,
                    target.ingredient(), req.mass(), target.id(), version, Instant.parse(now))));
        });
    }

    /**
     * 批次称量状态：各成分需求量、实际量、差额与占比偏差，以及整体是否满足配方。
     */
    public WeighingStatusResponse status(String batchKey) {
        batches.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        RecipeRepository.BindingRow binding = recipes.findBinding(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次未绑定配方: " + batchKey));
        return computeStatus(batchKey, binding);
    }

    /**
     * 放行配方门禁：批次已绑定配方时，必须每种成分齐全、总质量精确等于目标、
     * 每项实际占比偏差不超过 0.1 个百分点（精确比较，不提前舍入），
     * 否则抛 422 并携带各成分差额；未绑定配方的批次不施加门禁。
     */
    public void assertReleaseReady(String batchKey) {
        Optional<RecipeRepository.BindingRow> binding = recipes.findBinding(batchKey);
        if (binding.isEmpty()) {
            return;
        }
        WeighingStatusResponse status = computeStatus(batchKey, binding.get());
        if (!status.satisfied()) {
            throw ApiException.unprocessable("投料不满足绑定配方，禁止放行", status.ingredients());
        }
    }

    /**
     * 批次配方绑定查询，供拆分流程判断是否需按质量比例继承。
     */
    public Optional<RecipeRepository.BindingRow> findBinding(String batchKey) {
        return recipes.findBinding(batchKey);
    }
    /**
     * 拆分继承计划：子批按目标质量比例精确继承父批各成分有效质量。
     * 子批目标质量之和须精确等于父批目标总质量，且每个成分分摊结果须为
     * 不超过 6 位小数的精确值，否则抛 422；子批 targetMass 缺失或非法抛 400。
     */
    public List<SplitInheritance> planSplitInheritance(String parentKey,
                                                       List<SplitRequest.ChildSpec> children) {
        RecipeRepository.BindingRow binding = recipes.findBinding(parentKey)
                .orElseThrow(() -> ApiException.unprocessable("父批未绑定配方: " + parentKey));
        for (SplitRequest.ChildSpec child : children) {
            if (child.targetMass() == null) {
                throw ApiException.badRequest("父批已绑定配方，子批必须提供 targetMass: "
                        + child.batchKey());
            }
            validateMass(child.targetMass(), "targetMass");
        }
        BigDecimal childSum = children.stream()
                .map(SplitRequest.ChildSpec::targetMass)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (childSum.compareTo(binding.targetMass()) != 0) {
            throw ApiException.unprocessable("子批目标总质量之和 " + childSum.toPlainString()
                    + " 不等于父批目标总质量 " + binding.targetMass().toPlainString()
                    + "，无法守恒拆分");
        }
        List<RecipeRepository.IngredientRow> ingredients = recipes.findIngredients(binding.recipeKey());
        Map<String, BigDecimal> totals = effectiveTotals(parentKey);
        List<SplitInheritance> plan = new ArrayList<>(children.size());
        for (SplitRequest.ChildSpec child : children) {
            List<InheritedWeighing> inherited = new ArrayList<>(ingredients.size());
            for (RecipeRepository.IngredientRow ingredient : ingredients) {
                BigDecimal total = totals.getOrDefault(ingredient.ingredient(), BigDecimal.ZERO);
                BigDecimal share;
                try {
                    share = total.multiply(child.targetMass()).divide(binding.targetMass());
                } catch (ArithmeticException e) {
                    throw unconservable(ingredient.ingredient());
                }
                if (share.scale() > MAX_MASS_SCALE) {
                    throw unconservable(ingredient.ingredient());
                }
                inherited.add(new InheritedWeighing(ingredient.ingredient(), share));
            }
            plan.add(new SplitInheritance(child.batchKey(), child.targetMass(), inherited));
        }
        return plan;
    }

    /**
     * 应用拆分继承：为子批建立配方绑定并写入继承称量记录（来源 SPLIT）。
     * 与拆分主流程同事务提交。
     */
    public void applySplitInheritance(SplitInheritance plan, String recipeKey,
                                      String commandKey, String now) {
        recipes.insertBinding(plan.childKey(), recipeKey, plan.targetMass(), now);
        for (InheritedWeighing inherited : plan.ingredientMasses()) {
            recipes.insertWeighing(new RecipeRepository.WeighingRow(0L, plan.childKey(),
                    commandKey, inherited.ingredient(), inherited.mass(), null, null,
                    "SPLIT", "SPLIT", now));
        }
    }

    /**
     * 单个子批的拆分继承计划：目标总质量 + 各成分继承质量（单位克）。
     */
    public record SplitInheritance(String childKey, BigDecimal targetMass,
                                   List<InheritedWeighing> ingredientMasses) {
    }

    /**
     * 子批继承的单成分质量（单位克，最多 6 位小数的精确值）。
     */
    public record InheritedWeighing(String ingredient, BigDecimal mass) {
    }

    /**
     * 计算批次称量状态。占比偏差判定使用精确比较：
     * |actual*100 - pct*target| <= 0.1*target，不提前舍入；
     * deviationPoints 仅为展示保留 6 位小数。
     */
    private WeighingStatusResponse computeStatus(String batchKey,
                                                 RecipeRepository.BindingRow binding) {
        List<RecipeRepository.IngredientRow> ingredients = recipes.findIngredients(binding.recipeKey());
        Map<String, BigDecimal> totals = effectiveTotals(batchKey);
        BigDecimal totalMass = totals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<WeighingStatusResponse.IngredientStatus> statuses = new ArrayList<>(ingredients.size());
        boolean allPresent = true;
        boolean allWithin = true;
        for (RecipeRepository.IngredientRow ingredient : ingredients) {
            BigDecimal required = ingredient.percentage()
                    .multiply(binding.targetMass()).divide(HUNDRED);
            BigDecimal actual = totals.get(ingredient.ingredient());
            boolean present = actual != null;
            boolean within = false;
            BigDecimal difference = null;
            BigDecimal deviationPoints = null;
            if (present) {
                BigDecimal lhs = actual.multiply(HUNDRED)
                        .subtract(ingredient.percentage().multiply(binding.targetMass())).abs();
                BigDecimal rhs = TOLERANCE_POINTS.multiply(binding.targetMass());
                within = lhs.compareTo(rhs) <= 0;
                deviationPoints = lhs.divide(binding.targetMass(), 6, RoundingMode.HALF_UP);
                difference = actual.subtract(required);
            }
            allPresent = allPresent && present;
            allWithin = allWithin && within;
            statuses.add(new WeighingStatusResponse.IngredientStatus(ingredient.ingredient(),
                    ingredient.percentage(), required, actual, difference, deviationPoints, within));
        }
        boolean satisfied = allPresent && allWithin
                && totalMass.compareTo(binding.targetMass()) == 0;
        return new WeighingStatusResponse(batchKey, binding.recipeKey(), binding.targetMass(),
                totalMass, recipes.countWeighings(batchKey), satisfied, statuses);
    }

    /**
     * 各成分当前有效称量累计（单位克）。
     */
    private Map<String, BigDecimal> effectiveTotals(String batchKey) {
        Map<String, BigDecimal> totals = new HashMap<>();
        for (RecipeRepository.WeighingRow row : recipes.findEffectiveWeighings(batchKey)) {
            totals.merge(row.ingredient(), row.mass(), BigDecimal::add);
        }
        return totals;
    }

    /**
     * 锁定批次并校验称量链可写：不存在抛 404；已放行/已召回/已拆分/已拒绝抛 409；
     * 祖先被召回抛 422。行锁使称量/更正与拆分、批准互斥。
     */
    private BatchRepository.BatchRow lockWeighableBatch(String batchKey) {
        BatchRepository.BatchRow batch = batches.findBatchForUpdate(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        BatchStatus status = BatchStatus.valueOf(batch.status());
        if (status == BatchStatus.RELEASED || status == BatchStatus.RECALLED
                || status == BatchStatus.SPLIT || status == BatchStatus.REJECTED) {
            throw ApiException.conflict("批次状态 " + status + " 称量链已冻结，禁止称量或更正");
        }
        lineageGuard.assertNoRecalledAncestor(batchKey);
        return batch;
    }

    /**
     * 期望版本校验：与当前称量链版本（称量记录总数）不一致抛 409。
     */
    private void assertVersion(String batchKey, long expectedVersion) {
        long current = recipes.countWeighings(batchKey);
        if (current != expectedVersion) {
            throw ApiException.conflict("期望称量链版本 " + expectedVersion
                    + " 与当前版本 " + current + " 不一致");
        }
    }

    private void validateMass(BigDecimal mass, String field) {
        if (mass.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest(field + " 必须为正值: " + mass.toPlainString());
        }
        if (mass.scale() > MAX_MASS_SCALE) {
            throw ApiException.badRequest(field + " 最多 6 位小数: " + mass.toPlainString());
        }
    }

    private void validatePercentage(BigDecimal percentage) {
        if (percentage.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("percentage 必须为正值: " + percentage.toPlainString());
        }
        if (percentage.scale() > MAX_PERCENTAGE_SCALE) {
            throw ApiException.badRequest("percentage 最多 4 位小数: " + percentage.toPlainString());
        }
    }

    private String requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        return operator.trim();
    }

    private ApiException unconservable(String ingredient) {
        return ApiException.unprocessable("成分 " + ingredient
                + " 的质量无法按子批质量比例在 6 位小数内守恒拆分");
    }

    private Map<String, Object> baseFingerprint(String op, String operator, String resource) {
        Map<String, Object> fp = new TreeMap<>();
        fp.put("op", op);
        fp.put("operator", operator);
        fp.put("resource", resource);
        return fp;
    }

    /**
     * 结构化指纹：有序映射序列化为 JSON 后取 SHA-256，避免分隔符拼接碰撞。
     */
    private String structuredFingerprint(Map<String, Object> fields) {
        String canonical;
        try {
            canonical = objectMapper.writeValueAsString(new TreeMap<>(fields));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("指纹序列化失败", e);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 幂等执行：同事务内先查 command_log，命中则按指纹返回快照或 409；
     * 未命中执行业务动作并写入快照。并发同键插入冲突时重试，读取已提交结果。
     */
    private StoredResponse executeIdempotent(String type, String requestId, String fingerprint,
                                             Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    var logged = loggedResponse(type, requestId, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    batches.insertCommand(new BatchRepository.CommandRow(type, requestId,
                            fingerprint, response.status(), response.body()), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同事务键冲突：回滚后重试，读取对方已提交的命令快照或业务结果
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + requestId);
    }

    /**
     * 查询命令快照：命中且指纹一致返回首次响应；指纹不一致抛 409；未命中返回空。
     */
    private Optional<StoredResponse> loggedResponse(String type, String requestId,
                                                    String fingerprint) {
        var existing = batches.findCommand(type, requestId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        BatchRepository.CommandRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已以不同参数使用: " + requestId);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
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