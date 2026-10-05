package com.example.starter.batch;

import com.example.starter.batch.dto.BindRecipeRequest;
import com.example.starter.batch.dto.BindingResponse;
import com.example.starter.batch.dto.CorrectWeighingRequest;
import com.example.starter.batch.dto.CreateRecipeRequest;
import com.example.starter.batch.dto.RecipeResponse;
import com.example.starter.batch.dto.RecipeStatusResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.WeighingRecordResponse;
import com.example.starter.batch.dto.WeighingRequest;
import com.example.starter.batch.dto.WeighingResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 配方与称量核心服务。
 *
 * <p>配方版本声明 2～6 种成分的质量百分比（正值、最多 4 位小数、总和恰为 100），创建后不可修改。
 * 批次绑定不可变配方版本与目标总质量（克，最多 6 位小数）；同成分多次称量累加，
 * 更正以替代量重算（旧记录失效、新记录链接）。放行要求每种成分齐全、有效称量总和等于
 * 目标总质量、每项实际占比偏差不超过 0.1 个百分点（判断不提前舍入）。
 *
 * <p>并发策略：称量/更正/绑定在事务内对 batch 行 SELECT ... FOR UPDATE 串行化，
 * 与检验/批准/召回/拆分互斥；requestId 幂等通过 request_log 主键 + 结构化 JSON 身份实现，
 * 同键同身份重放首次响应，同键异身份 409；仅成功请求写入 request_log，失败不占键。
 */
@Service
public class RecipeService {

    private static final int MAX_PERCENT_SCALE = 4;
    private static final int MAX_MASS_SCALE = 6;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.1");
    private static final MathContext MC = MathContext.DECIMAL128;

    private static final String OP_CREATE_RECIPE = "CREATE_RECIPE";
    private static final String OP_BIND_RECIPE = "BIND_RECIPE";
    private static final String OP_WEIGH = "WEIGH";
    private static final String OP_CORRECT = "CORRECT_WEIGHING";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final RecipeRepository repo;
    private final BatchRepository batchRepo;
    private final LineageGuard lineageGuard;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public RecipeService(RecipeRepository repo, BatchRepository batchRepo, LineageGuard lineageGuard,
                         PlatformTransactionManager transactionManager, ObjectMapper objectMapper) {
        this.repo = repo;
        this.batchRepo = batchRepo;
        this.lineageGuard = lineageGuard;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 创建配方版本：成分 2～6 种、名称不重复、百分比正值且最多 4 位小数、总和恰为 100；
     * 同一 recipeKey+version 已存在返回 409。
     */
    public StoredResponse createRecipe(String operator, CreateRecipeRequest req) {
        String actor = requireOperator(operator);
        Map<String, Object> identity = identity(OP_CREATE_RECIPE, actor,
                req.recipeKey() + ":" + req.version(),
                params("ingredients", req.ingredients().stream()
                        .map(i -> params("name", i.name(), "percentage", normalize(i.percentage())))
                        .toList()), null);
        return executeIdempotent(req.requestId(), identity, identityJson -> {
            if (req.version() < 1) {
                throw ApiException.badRequest("version 必须为正整数");
            }
            if (repo.findRecipe(req.recipeKey(), req.version()).isPresent()) {
                throw ApiException.conflict("配方版本已存在: " + req.recipeKey() + " v" + req.version());
            }
            List<CreateRecipeRequest.IngredientSpec> ingredients = req.ingredients();
            List<String> names = ingredients.stream().map(CreateRecipeRequest.IngredientSpec::name)
                    .map(String::trim).toList();
            if (names.size() != new java.util.HashSet<>(names).size()) {
                throw ApiException.badRequest("成分名称重复");
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (CreateRecipeRequest.IngredientSpec spec : ingredients) {
                BigDecimal pct = spec.percentage();
                requirePositive(pct, "成分百分比");
                requireScale(pct, MAX_PERCENT_SCALE, "成分百分比");
                sum = sum.add(pct);
            }
            if (sum.compareTo(HUNDRED) != 0) {
                throw ApiException.badRequest("成分百分比总和必须精确等于 100，实际为 " + sum.stripTrailingZeros().toPlainString());
            }
            String now = now();
            repo.insertRecipe(new RecipeRepository.RecipeRow(0L, req.recipeKey(), req.version(), now));
            List<RecipeResponse.IngredientEntry> entries = new ArrayList<>(ingredients.size());
            for (int i = 0; i < ingredients.size(); i++) {
                CreateRecipeRequest.IngredientSpec spec = ingredients.get(i);
                repo.insertIngredient(req.recipeKey(), req.version(), spec.name(), spec.percentage(), i + 1);
                entries.add(new RecipeResponse.IngredientEntry(spec.name(), spec.percentage()));
            }
            return new StoredResponse(201, toJson(new RecipeResponse(req.recipeKey(), req.version(),
                    entries, Instant.parse(now))));
        });
    }

    /**
     * 查询配方版本。
     */
    public RecipeResponse getRecipe(String recipeKey, int version) {
        RecipeRepository.RecipeRow row = repo.findRecipe(recipeKey, version)
                .orElseThrow(() -> ApiException.notFound("配方版本不存在: " + recipeKey + " v" + version));
        List<RecipeResponse.IngredientEntry> entries = repo.findIngredients(recipeKey, version).stream()
                .map(i -> new RecipeResponse.IngredientEntry(i.ingredient(), i.percentage()))
                .toList();
        return new RecipeResponse(row.recipeKey(), row.version(), entries, Instant.parse(row.createdAt()));
    }

    /**
     * 批次绑定配方版本与目标总质量：每批至多绑定一次；目标总质量单位克、正值、最多 6 位小数。
     */
    public StoredResponse bindRecipe(String batchKey, String operator, BindRecipeRequest req) {
        String actor = requireOperator(operator);
        Map<String, Object> identity = identity(OP_BIND_RECIPE, actor, batchKey,
                params("recipeKey", req.recipeKey(), "version", req.version(),
                        "targetMass", normalize(req.targetMass())), null);
        return executeIdempotent(req.requestId(), identity, identityJson -> {
            BatchRepository.BatchRow batch = lockBatch(batchKey);
            // 批次行锁后重查请求快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = loggedResponse(req.requestId(), identityJson);
            if (logged.isPresent()) {
                return logged.get();
            }
            lineageGuard.assertNoRecalledAncestor(batchKey);
            requireMutableStatus(batch.status());
            if (repo.findBinding(batchKey).isPresent()) {
                throw ApiException.conflict("批次已绑定配方，绑定不可修改: " + batchKey);
            }
            repo.findRecipe(req.recipeKey(), req.version())
                    .orElseThrow(() -> ApiException.notFound(
                            "配方版本不存在: " + req.recipeKey() + " v" + req.version()));
            requirePositive(req.targetMass(), "targetMass");
            requireScale(req.targetMass(), MAX_MASS_SCALE, "targetMass");
            String now = now();
            repo.insertBinding(new RecipeRepository.BindingRow(0L, batchKey, req.recipeKey(),
                    req.version(), req.targetMass(), 0L, now));
            BindingResponse body = new BindingResponse(batchKey, req.recipeKey(), req.version(),
                    req.targetMass(), 0L, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 称量：同成分多次称量累加；负量、未知成分或超精度（超过 6 位小数）返回 400；
     * 期望版本与称量链版本不一致返回 409。
     */
    public StoredResponse weigh(String batchKey, String operator, WeighingRequest req) {
        String actor = requireOperator(operator);
        Map<String, Object> identity = identity(OP_WEIGH, actor, batchKey,
                params("ingredient", req.ingredient(), "amount", normalize(req.amount())),
                req.expectedVersion());
        return executeIdempotent(req.requestId(), identity, identityJson -> {
            WeighContext ctx = lockAndPrepare(batchKey, req.expectedVersion(),
                    req.requestId(), identityJson);
            if (ctx.replay() != null) {
                return ctx.replay();
            }
            requireKnownIngredient(ctx, req.ingredient());
            requirePositive(req.amount(), "amount");
            requireScale(req.amount(), MAX_MASS_SCALE, "amount");
            String now = now();
            long id = repo.insertWeighing(new RecipeRepository.WeighingRow(0L, batchKey,
                    req.ingredient(), req.amount(), "MANUAL", null, null, actor, now));
            repo.incrementWeighingVersion(batchKey);
            WeighingResponse body = new WeighingResponse(id, batchKey, req.ingredient(), req.amount(),
                    "MANUAL", null, ctx.binding().weighingVersion() + 1, actor, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 称量更正：指定该成分最近一条有效称量记录，以替代量重算而不是重复累加；
     * 旧记录标记失效，新记录链接旧记录。负量或超精度返回 400；记录不存在返回 404；
     * 记录已失效或不是该成分最近有效记录返回 409。
     */
    public StoredResponse correct(String batchKey, String operator, CorrectWeighingRequest req) {
        String actor = requireOperator(operator);
        Map<String, Object> identity = identity(OP_CORRECT, actor, batchKey,
                params("weighingId", req.weighingId(), "amount", normalize(req.amount())),
                req.expectedVersion());
        return executeIdempotent(req.requestId(), identity, identityJson -> {
            WeighContext ctx = lockAndPrepare(batchKey, req.expectedVersion(),
                    req.requestId(), identityJson);
            if (ctx.replay() != null) {
                return ctx.replay();
            }
            requirePositive(req.amount(), "amount");
            requireScale(req.amount(), MAX_MASS_SCALE, "amount");
            RecipeRepository.WeighingRow target = repo.findWeighing(req.weighingId())
                    .filter(w -> w.batchKey().equals(batchKey))
                    .orElseThrow(() -> ApiException.notFound("称量记录不存在: " + req.weighingId()));
            if (target.supersededBy() != null) {
                throw ApiException.conflict("称量记录已被更正，不能再次更正: " + req.weighingId());
            }
            long latestEffectiveId = repo.findEffectiveWeighings(batchKey).stream()
                    .filter(w -> w.ingredient().equals(target.ingredient()))
                    .mapToLong(RecipeRepository.WeighingRow::id)
                    .max().orElse(-1L);
            if (latestEffectiveId != target.id()) {
                throw ApiException.conflict(
                        "只能更正成分 " + target.ingredient() + " 最近一条有效称量记录");
            }
            String now = now();
            long id = repo.insertWeighing(new RecipeRepository.WeighingRow(0L, batchKey,
                    target.ingredient(), req.amount(), "CORRECTION", target.id(), null, actor, now));
            repo.markSuperseded(target.id(), id);
            repo.incrementWeighingVersion(batchKey);
            WeighingResponse body = new WeighingResponse(id, batchKey, target.ingredient(),
                    req.amount(), "CORRECTION", target.id(), ctx.binding().weighingVersion() + 1,
                    actor, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 批次称量历史：含已失效记录，按提交顺序。
     */
    public List<WeighingRecordResponse> listWeighings(String batchKey) {
        batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return repo.findWeighings(batchKey).stream()
                .map(w -> new WeighingRecordResponse(w.id(), w.batchKey(), w.ingredient(), w.amount(),
                        w.source(), w.supersedesId(), w.supersededBy(), w.supersededBy() == null,
                        w.operatorId(), Instant.parse(w.createdAt())))
                .toList();
    }

    /**
     * 锁批次并校验称量前置条件：批次存在、（锁后重查请求快照）、祖先未召回、
     * 状态允许称量、已绑定配方、期望版本匹配。
     */
    private WeighContext lockAndPrepare(String batchKey, long expectedVersion,
                                        String requestId, String identityJson) {
        BatchRepository.BatchRow batch = lockBatch(batchKey);
        // 批次行锁后重查请求快照：并发同键请求在锁等待期间可能已由对方提交，
        // 此时须重放首次结果而不是因版本已前进误判冲突
        StoredResponse replay = loggedResponse(requestId, identityJson).orElse(null);
        if (replay != null) {
            return new WeighContext(null, List.of(), replay);
        }
        lineageGuard.assertNoRecalledAncestor(batchKey);
        requireMutableStatus(batch.status());
        RecipeRepository.BindingRow binding = repo.findBinding(batchKey)
                .orElseThrow(() -> ApiException.unprocessable("批次未绑定配方，不能称量: " + batchKey));
        if (binding.weighingVersion() != expectedVersion) {
            throw ApiException.conflict("期望版本 " + expectedVersion + " 与当前称量链版本 "
                    + binding.weighingVersion() + " 不一致");
        }
        List<RecipeRepository.IngredientRow> ingredients =
                repo.findIngredients(binding.recipeKey(), binding.recipeVersion());
        return new WeighContext(binding, ingredients, null);
    }

    private BatchRepository.BatchRow lockBatch(String batchKey) {
        return batchRepo.findBatchForUpdate(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
    }

    /**
     * 称量上下文：绑定与配方成分；replay 非空表示命中并发已提交的同键请求，直接重放。
     */
    private record WeighContext(RecipeRepository.BindingRow binding,
                                List<RecipeRepository.IngredientRow> ingredients,
                                StoredResponse replay) {
    }

    private void requireKnownIngredient(WeighContext ctx, String ingredient) {
        boolean known = ctx.ingredients().stream()
                .anyMatch(i -> i.ingredient().equals(ingredient));
        if (!known) {
            throw ApiException.badRequest("未知成分: " + ingredient);
        }
    }

    /**
     * 仅 QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW 允许绑定与称量变更；
     * RELEASED 冻结称量链，SPLIT/RECALLED/REJECTED 不允许变更。
     */
    private void requireMutableStatus(String status) {
        BatchStatus s = BatchStatus.valueOf(status);
        if (s != BatchStatus.QUARANTINED && s != BatchStatus.PENDING_RELEASE
                && s != BatchStatus.RELEASE_REVIEW) {
            throw ApiException.conflict("批次状态 " + status + " 不允许配方绑定或称量变更");
        }
    }

    /**
     * 批次配方状态：各成分目标/实际/差额与整体是否满足。
     */
    public RecipeStatusResponse recipeStatus(String batchKey) {
        batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        RecipeRepository.BindingRow binding = repo.findBinding(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次未绑定配方: " + batchKey));
        return computeStatus(binding);
    }

    /**
     * 放行门禁（在批次行锁内调用）：已绑定配方且未达成时抛 422，明细给出各成分差额；
     * 不允许只批准合格成分——任一成分缺失、超差或总质量不等都整体拒绝。
     */
    public void assertRecipeSatisfied(String batchKey) {
        Optional<RecipeRepository.BindingRow> binding = repo.findBinding(batchKey);
        if (binding.isEmpty()) {
            return;
        }
        RecipeStatusResponse status = computeStatus(binding.get());
        if (!status.satisfied()) {
            throw ApiException.unprocessable(
                    "配方未达成：须每种成分齐全、总质量等于目标且各项占比偏差不超过 0.1 个百分点",
                    status.ingredients());
        }
    }

    /**
     * 计算配方满足度：占比判断使用高精度除法，不提前舍入。
     */
    private RecipeStatusResponse computeStatus(RecipeRepository.BindingRow binding) {
        List<RecipeRepository.IngredientRow> ingredients =
                repo.findIngredients(binding.recipeKey(), binding.recipeVersion());
        Map<String, BigDecimal> totals = effectiveTotals(binding.batchKey());
        BigDecimal totalMass = totals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean allWeighed = true;
        boolean allWithin = true;
        List<RecipeStatusResponse.IngredientStatus> entries = new ArrayList<>(ingredients.size());
        for (RecipeRepository.IngredientRow ingredient : ingredients) {
            BigDecimal actual = totals.getOrDefault(ingredient.ingredient(), BigDecimal.ZERO);
            boolean weighed = totals.containsKey(ingredient.ingredient());
            BigDecimal requiredMass = binding.targetMass()
                    .multiply(ingredient.percentage()).divide(HUNDRED);
            BigDecimal actualPct = actual.divide(binding.targetMass(), MC).multiply(HUNDRED, MC);
            BigDecimal deviation = actualPct.subtract(ingredient.percentage(), MC);
            boolean within = deviation.abs(MC).compareTo(TOLERANCE) <= 0;
            allWeighed = allWeighed && weighed;
            allWithin = allWithin && within;
            entries.add(new RecipeStatusResponse.IngredientStatus(ingredient.ingredient(),
                    ingredient.percentage(), requiredMass, actual, actual.subtract(requiredMass),
                    deviation.setScale(8, java.math.RoundingMode.HALF_UP).stripTrailingZeros(),
                    weighed, within));
        }
        boolean satisfied = allWeighed && allWithin && totalMass.compareTo(binding.targetMass()) == 0;
        return new RecipeStatusResponse(binding.batchKey(), binding.recipeKey(),
                binding.recipeVersion(), binding.targetMass(), binding.weighingVersion(),
                totalMass, satisfied, entries);
    }

    /**
     * 各成分有效称量累计（未被更正替代的记录求和）。
     */
    private Map<String, BigDecimal> effectiveTotals(String batchKey) {
        Map<String, BigDecimal> totals = new HashMap<>();
        for (RecipeRepository.WeighingRow w : repo.findEffectiveWeighings(batchKey)) {
            totals.merge(w.ingredient(), w.amount(), BigDecimal::add);
        }
        return totals;
    }

    public Optional<RecipeRepository.BindingRow> findBinding(String batchKey) {
        return repo.findBinding(batchKey);
    }

    /**
     * 拆分继承（在父批行锁内调用）：校验子批质量并按比例精确继承父批各成分有效称量。
     * 子批质量之和须等于父批目标总质量；每个子批的各成分继承量 = 父批成分量 × 子批质量 ÷
     * 父批目标总质量，必须在 6 位小数内精确表示，否则整次拆分 422。
     *
     * @return 每个子批的成分继承量（与子批顺序一致）
     */
    public List<Map<String, BigDecimal>> planSplitInheritance(RecipeRepository.BindingRow parentBinding,
                                                              List<SplitRequest.ChildSpec> children) {
        for (SplitRequest.ChildSpec child : children) {
            if (child.mass() == null) {
                throw ApiException.badRequest("父批已绑定配方，子批必须给出 mass: " + child.batchKey());
            }
            requirePositive(child.mass(), "子批 mass");
            requireScale(child.mass(), MAX_MASS_SCALE, "子批 mass");
        }
        BigDecimal sum = children.stream().map(SplitRequest.ChildSpec::mass)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(parentBinding.targetMass()) != 0) {
            throw ApiException.unprocessable("子批质量之和 " + sum.stripTrailingZeros().toPlainString()
                    + " 不等于父批目标总质量 " + parentBinding.targetMass().stripTrailingZeros().toPlainString()
                    + "，无法守恒拆分");
        }
        Map<String, BigDecimal> parentTotals = effectiveTotals(parentBinding.batchKey());
        List<Map<String, BigDecimal>> plan = new ArrayList<>(children.size());
        for (SplitRequest.ChildSpec child : children) {
            Map<String, BigDecimal> inherited = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> entry : parentTotals.entrySet()) {
                BigDecimal share;
                try {
                    share = entry.getValue().multiply(child.mass())
                            .divide(parentBinding.targetMass());
                } catch (ArithmeticException e) {
                    throw ApiException.unprocessable("成分 " + entry.getKey()
                            + " 按子批质量比例无法在 6 位小数内精确守恒，拒绝拆分");
                }
                if (share.scale() > MAX_MASS_SCALE) {
                    throw ApiException.unprocessable("成分 " + entry.getKey()
                            + " 按子批质量比例无法在 6 位小数内精确守恒，拒绝拆分");
                }
                inherited.put(entry.getKey(), share);
            }
            plan.add(inherited);
        }
        return plan;
    }

    /**
     * 拆分落库（在父批行锁内调用）：为子批写入配方绑定与继承称量记录。
     */
    public void applySplitInheritance(RecipeRepository.BindingRow parentBinding, String childKey,
                                      BigDecimal childMass, Map<String, BigDecimal> inherited,
                                      String operator, String now) {
        repo.insertBinding(new RecipeRepository.BindingRow(0L, childKey, parentBinding.recipeKey(),
                parentBinding.recipeVersion(), childMass, 0L, now));
        for (Map.Entry<String, BigDecimal> entry : inherited.entrySet()) {
            repo.insertWeighing(new RecipeRepository.WeighingRow(0L, childKey, entry.getKey(),
                    entry.getValue(), "SPLIT_INHERIT", null, null, operator, now));
        }
    }

    /**
     * 幂等执行：同事务内先查 request_log，命中则按结构化身份返回快照或 409；
     * 未命中执行业务动作并写入快照，业务与去重原子提交。
     * 并发同键插入冲突时回滚重试，读取已提交结果，不泄露唯一键异常。
     * action 接收身份 JSON，用于在资源行锁后重查快照（锁等待期间对方可能已提交）。
     */
    private StoredResponse executeIdempotent(String requestId, Map<String, Object> identity,
                                             Function<String, StoredResponse> action) {
        String identityJson = toJson(identity);
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    var logged = loggedResponse(requestId, identityJson);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.apply(identityJson);
                    repo.insertRequest(new RecipeRepository.RequestRow(requestId, identityJson,
                            response.status(), response.body()), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同键冲突：回滚后重试，读取对方已提交的请求快照
            }
        }
        throw ApiException.conflict("请求并发冲突，请重试: " + requestId);
    }

    /**
     * 查询请求快照：命中且身份一致返回首次响应；身份不一致抛 409；未命中返回空。
     */
    private Optional<StoredResponse> loggedResponse(String requestId, String identityJson) {
        var existing = repo.findRequest(requestId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        RecipeRepository.RequestRow row = existing.get();
        if (!row.identityJson().equals(identityJson)) {
            throw ApiException.conflict("requestId 已以不同参数使用: " + requestId);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
    }

    /**
     * 结构化身份：操作、操作者、资源、全部参数与期望版本；序列化为 JSON 比较，
     * 避免分隔符拼接碰撞。
     */
    private Map<String, Object> identity(String operation, String operator, String resource,
                                         Map<String, Object> params, Long expectedVersion) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", operation);
        identity.put("operator", operator);
        identity.put("resource", resource);
        identity.put("params", params);
        identity.put("expectedVersion", expectedVersion);
        return identity;
    }

    /**
     * 有序参数表：保证身份 JSON 序列化顺序稳定。
     */
    private Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    private String requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        return operator.trim();
    }

    private void requirePositive(BigDecimal value, String field) {
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest(field + " 必须为正值");
        }
    }

    private void requireScale(BigDecimal value, int maxScale, String field) {
        if (value.scale() > maxScale) {
            throw ApiException.badRequest(field + " 最多 " + maxScale + " 位小数");
        }
    }

    /**
     * 规范化数值用于身份比较：去除尾随零，避免 50 与 50.0 被判为不同参数。
     */
    private String normalize(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
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
