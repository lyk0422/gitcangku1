package com.example.starter.batch;

import com.example.starter.batch.dto.CreateRecipeRequest;
import com.example.starter.batch.dto.CorrectWeighingRequest;
import com.example.starter.batch.dto.IngredientDifference;
import com.example.starter.batch.dto.RecipeResponse;
import com.example.starter.batch.dto.WeighRequest;
import com.example.starter.batch.dto.WeighingChainResponse;
import com.example.starter.batch.dto.WeighingResponse;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 配方称量核心服务：配方版本创建、称量与更正、放行配方门禁、拆批质量守恒继承。
 *
 * <p>并发策略：称量/更正在事务内对 batch 行 SELECT ... FOR UPDATE 串行化，与拆分、
 * 批准互斥；requestId 幂等复用 command_log（仅成功命令占键），身份指纹为
 * 操作+操作者+资源+全部参数+期望版本 的结构化 JSON 的 SHA-256，避免分隔符碰撞。
 */
@Service
public class RecipeService {

    private static final String CMD_RECIPE = "CREATE_RECIPE";
    private static final String CMD_WEIGH = "WEIGH";
    private static final String CMD_CORRECT = "CORRECT_WEIGH";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final RecipeRepository repo;
    private final BatchRepository batchRepo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public RecipeService(RecipeRepository repo, BatchRepository batchRepo,
                         PlatformTransactionManager transactionManager,
                         ObjectMapper objectMapper) {
        this.repo = repo;
        this.batchRepo = batchRepo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 创建配方版本：2～6 种成分，百分比最多 4 位小数、正值、合计精确等于 100；
     * recipeKey 全局唯一且不可变，重复创建返回 409。
     */
    public StoredResponse createRecipe(CreateRecipeRequest req) {
        List<CreateRecipeRequest.IngredientSpec> ingredients = req.ingredients();
        Set<String> names = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (CreateRecipeRequest.IngredientSpec spec : ingredients) {
            String name = spec.name().trim();
            if (!names.add(name)) {
                throw ApiException.badRequest("成分名称重复: " + name);
            }
            checkPercentage(spec.percentage());
            sum = sum.add(spec.percentage());
        }
        if (sum.compareTo(HUNDRED) != 0) {
            throw ApiException.badRequest("成分百分比合计必须精确等于 100，实际为 " + sum.toPlainString());
        }
        Map<String, Object> params = new TreeMap<>();
        List<Object> items = new ArrayList<>();
        for (CreateRecipeRequest.IngredientSpec spec : ingredients) {
            Map<String, Object> item = new TreeMap<>();
            item.put("name", spec.name().trim());
            item.put("percentage", spec.percentage().toPlainString());
            items.add(item);
        }
        params.put("ingredients", items);
        String fingerprint = fingerprint(CMD_RECIPE, req.operator().trim(), req.recipeKey(), params, null);
        return executeIdempotent(CMD_RECIPE, req.requestId(), fingerprint, () -> {
            repo.findRecipe(req.recipeKey()).ifPresent(r -> {
                throw ApiException.conflict("recipeKey 已存在，配方版本不可变: " + req.recipeKey());
            });
            String now = now();
            repo.insertRecipe(new RecipeRepository.RecipeRow(0L, req.recipeKey(),
                    req.operator().trim(), now));
            List<RecipeResponse.IngredientEntry> entries = new ArrayList<>();
            for (int i = 0; i < ingredients.size(); i++) {
                CreateRecipeRequest.IngredientSpec spec = ingredients.get(i);
                repo.insertIngredient(req.recipeKey(), spec.name().trim(),
                        spec.percentage().toPlainString(), i + 1);
                entries.add(new RecipeResponse.IngredientEntry(spec.name().trim(),
                        spec.percentage().toPlainString()));
            }
            RecipeResponse body = new RecipeResponse(req.recipeKey(), entries, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 批次的配方绑定（存在与否均可查）；供创建响应、拆分守恒与历史组装使用。
     */
    public Optional<RecipeRepository.BatchRecipeRow> findBinding(String batchKey) {
        return repo.findBatchRecipe(batchKey);
    }

    /**
     * 批次创建时绑定配方版本与目标总质量（同事务）；配方不存在返回 422。绑定不可变。
     */
    public void bindRecipe(String batchKey, String recipeKey, BigDecimal targetMassG, String now) {
        repo.findRecipe(recipeKey)
                .orElseThrow(() -> ApiException.unprocessable("配方版本不存在: " + recipeKey));
        repo.insertBatchRecipe(new RecipeRepository.BatchRecipeRow(0L, batchKey, recipeKey,
                targetMassG.toPlainString(), now));
    }

    /**
     * 查询配方版本；不存在返回 404。
     */
    public RecipeResponse getRecipe(String recipeKey) {
        repo.findRecipe(recipeKey)
                .orElseThrow(() -> ApiException.notFound("配方版本不存在: " + recipeKey));
        List<RecipeResponse.IngredientEntry> entries = repo.findIngredients(recipeKey).stream()
                .map(i -> new RecipeResponse.IngredientEntry(i.ingredient(), i.percentage()))
                .toList();
        String createdAt = repo.findRecipe(recipeKey).get().createdAt();
        return new RecipeResponse(recipeKey, entries, Instant.parse(createdAt));
    }

    /**
     * 称量：同成分多次称量累加。未知成分、负量、超 6 位小数返回 400；
     * 批次未绑定配方返回 422；已拒绝/放行/召回/拆分的批次称量链冻结返回 409。
     */
    public StoredResponse weigh(String batchKey, WeighRequest req) {
        checkMass(req.massG(), "massG");
        Map<String, Object> params = new TreeMap<>();
        params.put("ingredient", req.ingredient().trim());
        params.put("massG", req.massG().toPlainString());
        String fingerprint = fingerprint(CMD_WEIGH, req.operator().trim(), batchKey, params,
                req.expectedVersion());
        return executeIdempotent(CMD_WEIGH, req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            RecipeRepository.BatchRecipeRow binding = bindingOf(batchKey);
            assertWeighingOpen(batch);
            assertNoRecalledAncestor(batchKey);
            assertVersion(batchKey, req.expectedVersion());
            List<RecipeRepository.IngredientRow> ingredients = repo.findIngredients(binding.recipeKey());
            String ingredient = req.ingredient().trim();
            boolean known = ingredients.stream().anyMatch(i -> i.ingredient().equals(ingredient));
            if (!known) {
                throw ApiException.badRequest("成分不属于批次绑定配方: " + ingredient);
            }
            String now = now();
            long weighingId = repo.insertWeighing(new RecipeRepository.WeighingRow(0L, batchKey,
                    req.requestId(), req.operator().trim(), ingredient,
                    req.massG().toPlainString(), null, null, now));
            WeighingResponse body = new WeighingResponse(weighingId, batchKey, ingredient,
                    req.massG().toPlainString(), null, repo.weighingVersion(batchKey),
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 称量更正：追加一条替代记录重算，而不是重复累加。weighingId 必须为该成分
     * 当前最近的有效称量记录，否则 409；已拆父批与已放行批次称量链冻结，返回 409。
     */
    public StoredResponse correct(String batchKey, CorrectWeighingRequest req) {
        checkMass(req.newMassG(), "newMassG");
        Map<String, Object> params = new TreeMap<>();
        params.put("weighingId", String.valueOf(req.weighingId()));
        params.put("newMassG", req.newMassG().toPlainString());
        String fingerprint = fingerprint(CMD_CORRECT, req.operator().trim(), batchKey, params,
                req.expectedVersion());
        return executeIdempotent(CMD_CORRECT, req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            bindingOf(batchKey);
            assertWeighingOpen(batch);
            assertNoRecalledAncestor(batchKey);
            assertVersion(batchKey, req.expectedVersion());
            RecipeRepository.WeighingRow target = repo.findWeighing(req.weighingId())
                    .filter(w -> w.batchKey().equals(batchKey))
                    .orElseThrow(() -> ApiException.notFound(
                            "称量记录不存在: " + req.weighingId()));
            if (target.supersededBy() != null) {
                throw ApiException.conflict("称量记录已被更正替代，不能再次更正: " + req.weighingId());
            }
            List<RecipeRepository.WeighingRow> valid = repo.findValidWeighings(batchKey).stream()
                    .filter(w -> w.ingredient().equals(target.ingredient()))
                    .toList();
            long latestValidId = valid.get(valid.size() - 1).id();
            if (latestValidId != target.id()) {
                throw ApiException.conflict(
                        "只能更正成分 " + target.ingredient() + " 的最近有效称量记录: " + latestValidId);
            }
            String now = now();
            long newId = repo.insertWeighing(new RecipeRepository.WeighingRow(0L, batchKey,
                    req.requestId(), req.operator().trim(), target.ingredient(),
                    req.newMassG().toPlainString(), target.id(), null, now));
            repo.markSuperseded(target.id(), newId);
            WeighingResponse body = new WeighingResponse(newId, batchKey, target.ingredient(),
                    req.newMassG().toPlainString(), target.id(), repo.weighingVersion(batchKey),
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 放行配方门禁：批次绑定配方时，每种成分齐全、有效称量总量精确等于目标总质量、
     * 且每项实际占比偏差不超过 0.1 个百分点（精确十进制比较，不提前舍入）才放行；
     * 否则抛 RecipeGateException（422），携带全部成分差额，不允许只批准合格成分。
     */
    public void assertRecipeSatisfied(String batchKey) {
        Optional<RecipeRepository.BatchRecipeRow> binding = repo.findBatchRecipe(batchKey);
        if (binding.isEmpty()) {
            return;
        }
        List<IngredientDifference> differences = evaluate(batchKey, binding.get());
        if (!isSatisfied(batchKey, binding.get(), differences)) {
            throw new RecipeGateException("投料不达配方，禁止放行: " + batchKey, differences);
        }
    }

    /**
     * 批次称量链视图：绑定信息、全部称量记录（含已失效）、各成分差额与门禁结论。
     */
    public WeighingChainResponse chain(String batchKey) {
        batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Optional<RecipeRepository.BatchRecipeRow> binding = repo.findBatchRecipe(batchKey);
        List<WeighingChainResponse.WeighingEntry> records = repo.findWeighings(batchKey).stream()
                .map(w -> new WeighingChainResponse.WeighingEntry(w.id(), w.ingredient(),
                        w.massG(), w.operator(), w.supersedesId(), w.supersededBy() == null,
                        w.createdAt()))
                .toList();
        if (binding.isEmpty()) {
            return new WeighingChainResponse(batchKey, null, null, 0L, records, List.of(), false);
        }
        List<IngredientDifference> differences = evaluate(batchKey, binding.get());
        boolean satisfied = isSatisfied(batchKey, binding.get(), differences);
        return new WeighingChainResponse(batchKey, binding.get().recipeKey(),
                binding.get().targetMassG(), repo.weighingVersion(batchKey), records,
                differences, satisfied);
    }

    /**
     * 拆批继承计划：按子批目标质量占父批目标总质量的比例精确继承父批各成分有效称量。
     * 子批目标合计必须精确等于父批目标；任一成分分摊无法在 6 位小数内精确表示时返回 422。
     * 返回 子批键 → (成分 → 继承质量)。
     */
    public Map<String, Map<String, BigDecimal>> planInheritance(String parentKey,
                                                                List<String> childKeys,
                                                                List<BigDecimal> childTargets) {
        RecipeRepository.BatchRecipeRow binding = repo.findBatchRecipe(parentKey)
                .orElseThrow(() -> ApiException.unprocessable("批次未绑定配方: " + parentKey));
        BigDecimal parentTarget = new BigDecimal(binding.targetMassG());
        BigDecimal sum = childTargets.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(parentTarget) != 0) {
            throw ApiException.unprocessable("子批目标质量合计 " + sum.toPlainString()
                    + " 必须精确等于父批目标总质量 " + parentTarget.toPlainString());
        }
        Map<String, BigDecimal> totals = effectiveTotals(parentKey);
        Map<String, Map<String, BigDecimal>> plan = new LinkedHashMap<>();
        for (int c = 0; c < childKeys.size(); c++) {
            Map<String, BigDecimal> masses = new LinkedHashMap<>();
            for (RecipeRepository.IngredientRow ing : repo.findIngredients(binding.recipeKey())) {
                BigDecimal product = totals.getOrDefault(ing.ingredient(), BigDecimal.ZERO)
                        .multiply(childTargets.get(c));
                BigDecimal share;
                try {
                    share = product.divide(parentTarget, 6, RoundingMode.UNNECESSARY);
                } catch (ArithmeticException e) {
                    throw ApiException.unprocessable("拆批无法在 6 位小数内守恒：成分 "
                            + ing.ingredient() + " 分摊到子批 " + childKeys.get(c));
                }
                masses.put(ing.ingredient(), share);
            }
            plan.put(childKeys.get(c), masses);
        }
        return plan;
    }

    /**
     * 写入子批的配方绑定与继承称量记录（与拆分同事务）；操作人记为 system-split。
     */
    public void insertInheritedWeighings(String childKey, String recipeKey, BigDecimal childTarget,
                                         Map<String, BigDecimal> masses, String splitCommandKey,
                                         String now) {
        repo.insertBatchRecipe(new RecipeRepository.BatchRecipeRow(0L, childKey, recipeKey,
                childTarget.toPlainString(), now));
        int seq = 0;
        for (Map.Entry<String, BigDecimal> entry : masses.entrySet()) {
            seq++;
            repo.insertWeighing(new RecipeRepository.WeighingRow(0L, childKey,
                    "SPLIT:" + splitCommandKey + ":" + childKey + ":" + seq, "system-split",
                    entry.getKey(), entry.getValue().toPlainString(), null, null, now));
        }
    }

    /**
     * 各成分有效称量合计（仅统计未被更正替代的记录）。
     */
    private Map<String, BigDecimal> effectiveTotals(String batchKey) {
        Map<String, BigDecimal> totals = new HashMap<>();
        for (RecipeRepository.WeighingRow w : repo.findValidWeighings(batchKey)) {
            totals.merge(w.ingredient(), new BigDecimal(w.massG()), BigDecimal::add);
        }
        return totals;
    }

    private BigDecimal totalMass(String batchKey) {
        return effectiveTotals(batchKey).values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 逐成分评估差额：expected = 目标总质量 × 百分比 / 100（精确）；
     * 占比偏差 |actual/total − pct/100| ≤ 0.001 用精确十进制乘法判定
     * （|1000·actual − 10·pct·total| ≤ total），不做任何提前舍入。
     */
    private List<IngredientDifference> evaluate(String batchKey,
                                                RecipeRepository.BatchRecipeRow binding) {
        BigDecimal target = new BigDecimal(binding.targetMassG());
        Map<String, BigDecimal> totals = effectiveTotals(batchKey);
        BigDecimal total = totals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<IngredientDifference> result = new ArrayList<>();
        for (RecipeRepository.IngredientRow ing : repo.findIngredients(binding.recipeKey())) {
            BigDecimal pct = new BigDecimal(ing.percentage());
            BigDecimal actual = totals.getOrDefault(ing.ingredient(), BigDecimal.ZERO);
            BigDecimal expected = target.multiply(pct).divide(HUNDRED);
            boolean ok = total.compareTo(BigDecimal.ZERO) > 0
                    && actual.multiply(new BigDecimal("1000"))
                            .subtract(pct.multiply(BigDecimal.TEN).multiply(total)).abs()
                            .compareTo(total) <= 0;
            result.add(new IngredientDifference(ing.ingredient(), pct.toPlainString(),
                    expected.toPlainString(), actual.toPlainString(),
                    actual.subtract(expected).toPlainString(), ok));
        }
        return result;
    }

    /**
     * 门禁结论：每种成分齐全（至少一条有效称量）、总量精确等于目标、各成分占比偏差均在容差内。
     */
    private boolean isSatisfied(String batchKey, RecipeRepository.BatchRecipeRow binding,
                                List<IngredientDifference> differences) {
        Set<String> weighed = effectiveTotals(batchKey).keySet();
        boolean allPresent = repo.findIngredients(binding.recipeKey()).stream()
                .allMatch(i -> weighed.contains(i.ingredient()));
        boolean totalMatches = totalMass(batchKey)
                .compareTo(new BigDecimal(binding.targetMassG())) == 0;
        return allPresent && totalMatches
                && differences.stream().allMatch(IngredientDifference::withinTolerance);
    }

    private RecipeRepository.BatchRecipeRow bindingOf(String batchKey) {
        return repo.findBatchRecipe(batchKey)
                .orElseThrow(() -> ApiException.unprocessable("批次未绑定配方: " + batchKey));
    }

    /**
     * 称量链开放状态：仅 QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW 可称量或更正；
     * 已拒绝/放行/召回/拆分即冻结。
     */
    private void assertWeighingOpen(BatchRepository.BatchRow batch) {
        BatchStatus status = BatchStatus.valueOf(batch.status());
        if (status == BatchStatus.REJECTED || status == BatchStatus.RELEASED
                || status == BatchStatus.RECALLED || status == BatchStatus.SPLIT) {
            throw ApiException.conflict("批次状态 " + status + " 称量链已冻结，禁止称量与更正");
        }
    }

    private void assertVersion(String batchKey, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != repo.weighingVersion(batchKey)) {
            throw ApiException.conflict("期望称量链版本 " + expectedVersion + " 与当前版本 "
                    + repo.weighingVersion(batchKey) + " 不一致: " + batchKey);
        }
    }

    /**
     * 若任一级祖先已被召回则抛 422：后代批次禁止称量与更正（与检验/批准/拆分一致）。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        Map<String, String> parentOf = new HashMap<>();
        for (BatchRepository.LineageRow row : batchRepo.findAllLineage()) {
            parentOf.put(row.childKey(), row.parentKey());
        }
        Set<String> recalled = new HashSet<>(batchRepo.findRecalledKeys());
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            if (recalled.contains(current)) {
                throw ApiException.unprocessable(
                        "祖先批次 " + current + " 已召回，禁止称量与更正");
            }
        }
    }

    private void checkPercentage(BigDecimal percentage) {
        if (percentage.scale() > 4) {
            throw ApiException.badRequest("百分比最多 4 位小数: " + percentage.toPlainString());
        }
        if (percentage.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("百分比必须为正值: " + percentage.toPlainString());
        }
    }

    private void checkMass(BigDecimal mass, String field) {
        if (mass.scale() > 6) {
            throw ApiException.badRequest(field + " 最多 6 位小数: " + mass.toPlainString());
        }
        if (mass.compareTo(BigDecimal.ZERO) < 0) {
            throw ApiException.badRequest(field + " 不能为负: " + mass.toPlainString());
        }
    }

    /**
     * 幂等执行：同事务内先查 command_log，命中则按指纹返回快照或 409；
     * 未命中执行业务动作并写入快照，业务与去重原子提交，失败不占键。
     * 并发同键插入冲突时回滚重试，读取对方已提交结果，不泄露唯一键异常。
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
                    batchRepo.insertCommand(new BatchRepository.CommandRow(type, requestId,
                            fingerprint, response.status(), response.body()), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同事务键冲突：回滚后重试，读取对方已提交的命令快照或业务结果
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + requestId);
    }

    private Optional<StoredResponse> loggedResponse(String type, String requestId,
                                                    String fingerprint) {
        var existing = batchRepo.findCommand(type, requestId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        BatchRepository.CommandRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已以不同参数使用: " + requestId);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
    }

    /**
     * 结构化身份指纹：操作+操作者+资源+全部参数+期望版本 组成键有序的 JSON 后取 SHA-256；
     * 字符串经 JSON 转义，不存在分隔符碰撞。
     */
    private String fingerprint(String operation, String operator, String resource,
                               Map<String, Object> params, Long expectedVersion) {
        Map<String, Object> identity = new TreeMap<>();
        identity.put("operation", operation);
        identity.put("operator", operator);
        identity.put("resource", resource);
        identity.put("params", params);
        identity.put("expectedVersion", expectedVersion == null ? "" : expectedVersion.toString());
        return sha256(toJson(identity));
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
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
