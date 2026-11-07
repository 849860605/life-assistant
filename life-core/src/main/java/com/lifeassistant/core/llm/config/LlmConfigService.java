package com.lifeassistant.core.llm.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.config.LifeProperties;
import com.lifeassistant.core.crypto.FieldCipher;
import com.lifeassistant.core.llm.ChatModelRegistry;
import com.lifeassistant.core.llm.LlmCatalog;
import com.lifeassistant.core.llm.ModelSpec;
import com.lifeassistant.core.user.User;
import com.lifeassistant.core.user.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 后台的模型配置：提供方（Key 加密存）、模型（单价、App 里能不能选）、各用途用哪个模型，以及用户自己选的对话模型。
 * 第一次启动时把 application.yml 的 life.llm、life.models 导入数据库，之后以数据库为准；
 * 读的时候用内存里的快照，后台每次修改后重新加载。
 */
@Slf4j
@Service
@Order(0)
@RequiredArgsConstructor
public class LlmConfigService implements LlmCatalog, ApplicationRunner {

    /** 后台能分配的用途 */
    public static final List<String> ROLES = List.of(ChatModelRegistry.SKILL, ChatModelRegistry.ROUTER,
            ChatModelRegistry.VISION);
    private static final Set<String> TYPES = Set.of("openai", "dashscope");
    private static final Set<String> THINKING_STYLES = Set.of("QWEN", "TYPE", "OPENAI", "CUSTOM", "NONE");
    private static final Set<String> THINKING_LEVELS = Set.of("LOW", "MEDIUM", "HIGH", "CUSTOM");
    /** 导入时给常见提供方起的中文名 */
    private static final Map<String, String> NAMES = Map.of("dashscope", "通义百炼", "deepseek", "DeepSeek",
            "moonshot", "Kimi", "zhipu", "智谱", "doubao", "豆包", "openai", "OpenAI", "dots", "小红书点点");

    private final LlmProviderMapper providerMapper;
    private final LlmModelMapper modelMapper;
    private final LlmRoleMapper roleMapper;
    private final UserMapper userMapper;
    private final FieldCipher cipher;
    private final LifeProperties properties;
    private final ObjectMapper objectMapper;

    private volatile Snapshot snapshot;

    /** 内存里的配置快照：启用的提供方（Key 已解密）、全部模型、用途 → 模型 id。 */
    private record Snapshot(Map<String, LifeProperties.Provider> providers, Map<Long, LlmModel> models,
                            Map<String, Long> roles) {
    }

    /** 后台看到的提供方：Key 只给后 4 位。 */
    public record ProviderView(long id, String code, String name, String type, String baseUrl, String completionsPath,
                               String keyTail, boolean hasKey, String authHeader, String noThinking, String thinkingStyle,
                               boolean enabled, int sort, long models) {
    }

    /** 新建、修改提供方的内容：apiKey 为空表示不改（新建时就是没有 Key）。 */
    public record ProviderForm(String code, String name, String type, String baseUrl, String completionsPath,
                               String apiKey, String authHeader, String noThinking, String thinkingStyle, Boolean enabled,
                               Integer sort) {
    }

    /** 新建、修改模型的内容。 */
    public record ModelForm(String providerCode, String model, String displayName, String description, Boolean enabled,
                            Boolean userSelectable, Boolean supportsTools, BigDecimal priceInput,
                            BigDecimal priceCachedInput, BigDecimal priceOutput, Integer sort, Boolean thinking,
                            String thinkingLevel, Integer thinkingBudget) {
    }

    /** App 里可选的一个模型。 */
    public record Choice(long id, String name, String description) {
    }

    /** App 的模型选择：可选的模型、自己选的（没选为空）、默认用的（用户没选时）。 */
    public record Choices(List<Choice> models, Long selected, Long defaultId) {
    }

    // ---------------- LlmCatalog ----------------

    @Override
    public Map<String, LifeProperties.Provider> providers() {
        return snapshot().providers();
    }

    @Override
    public Map<String, String> roles() {
        Snapshot s = snapshot();
        Map<String, String> out = new LinkedHashMap<>();
        s.roles().forEach((role, id) -> {
            LlmModel m = s.models().get(id);
            if (m != null) {
                out.put(role, m.spec());
            }
        });
        return out;
    }

    @Override
    public LifeProperties.Price price(String modelId) {
        return snapshot().models().values().stream().filter(m -> m.spec().equals(modelId)).findFirst()
                .filter(m -> m.getPriceInput() != null || m.getPriceOutput() != null)
                .map(m -> {
                    LifeProperties.Price p = new LifeProperties.Price();
                    p.setInput(zero(m.getPriceInput()));
                    p.setCachedInput(m.getPriceCachedInput() == null ? zero(m.getPriceInput()) : m.getPriceCachedInput());
                    p.setOutput(zero(m.getPriceOutput()));
                    return p;
                }).orElse(null);
    }

    @Override
    public Thinking thinking(String modelId) {
        return snapshot().models().values().stream().filter(m -> m.spec().equals(modelId)).findFirst()
                .map(m -> new Thinking(Boolean.TRUE.equals(m.getThinking()), m.getThinkingLevel(), m.getThinkingBudget()))
                .orElse(null);
    }

    // ---------------- 启动导入 ----------------

    /** 启动时：数据库里还没有提供方，就把 yml 里的配置导入一次。 */
    @Override
    public void run(ApplicationArguments args) {
        if (providerMapper.selectCount(null) == 0) {
            importFromYaml();
        }
        reload();
    }

    /** 把 yml 的提供方（有 Key 的加密存）、单价和各用途的型号导入数据库。 */
    @Transactional
    public void importFromYaml() {
        LifeProperties.Llm llm = properties.getLlm();
        int sort = 0;
        for (Map.Entry<String, LifeProperties.Provider> e : llm.getProviders().entrySet()) {
            LifeProperties.Provider p = e.getValue();
            LlmProvider row = new LlmProvider();
            row.setCode(e.getKey());
            row.setName(NAMES.getOrDefault(e.getKey(), e.getKey()));
            row.setType(p.getType());
            row.setBaseUrl(blankToNull(p.getBaseUrl()));
            row.setCompletionsPath(blankToNull(p.getCompletionsPath()));
            row.setApiKeyEnc(StringUtils.hasText(p.getApiKey()) ? cipher.encrypt(p.getApiKey().trim()) : null);
            row.setAuthHeader(blankToNull(p.getAuthHeader()));
            row.setNoThinking(p.getNoThinking() == null || p.getNoThinking().isEmpty() ? null : toJson(p.getNoThinking()));
            row.setEnabled(true);
            row.setSort(sort++);
            providerMapper.insert(row);
        }

        LifeProperties.Models models = properties.getModels();
        Set<String> providerNames = llm.getProviders().keySet();
        String skill = parse(models.getSkill(), providerNames);
        Set<String> specs = new LinkedHashSet<>();
        specs.add(skill);
        specs.add(parse(models.getRouter(), providerNames));
        specs.add(parse(models.getVision(), providerNames));
        llm.getPricing().keySet().forEach(k -> specs.add(parse(k, providerNames)));
        Map<String, Long> ids = new LinkedHashMap<>();
        sort = 0;
        for (String spec : specs) {
            ModelSpec ms = ModelSpec.parse(spec, llm.getDefaultProvider(), providerNames);
            LifeProperties.Price price = llm.getPricing().get(spec);
            LlmModel m = new LlmModel();
            m.setProviderCode(ms.provider());
            m.setModel(ms.model());
            m.setDisplayName(ms.model());
            m.setEnabled(true);
            m.setUserSelectable(spec.equals(skill));
            m.setSupportsTools(true);
            if (price != null) {
                m.setPriceInput(price.getInput());
                m.setPriceCachedInput(price.getCachedInput());
                m.setPriceOutput(price.getOutput());
            }
            m.setSort(sort++);
            modelMapper.insert(m);
            ids.put(spec, m.getId());
        }
        putRole(ChatModelRegistry.SKILL, ids.get(skill));
        putRole(ChatModelRegistry.ROUTER, ids.get(parse(models.getRouter(), providerNames)));
        putRole(ChatModelRegistry.VISION, ids.get(parse(models.getVision(), providerNames)));
        log.info("已把 application.yml 的模型配置导入数据库：{} 个提供方，{} 个模型", llm.getProviders().size(), specs.size());
    }

    // ---------------- 提供方 ----------------

    /** 全部提供方（含停用的），按排序。 */
    public List<ProviderView> listProviders() {
        List<LlmModel> models = modelMapper.selectList(null);
        return providerMapper.selectList(new LambdaQueryWrapper<LlmProvider>()
                        .orderByAsc(LlmProvider::getSort).orderByAsc(LlmProvider::getId)).stream()
                .map(p -> {
                    String key = decrypt(p);
                    return new ProviderView(p.getId(), p.getCode(), p.getName(), p.getType(), p.getBaseUrl(),
                            p.getCompletionsPath(), key == null ? null : tail(key), key != null, p.getAuthHeader(),
                            p.getNoThinking(), p.getThinkingStyle(), Boolean.TRUE.equals(p.getEnabled()),
                            p.getSort() == null ? 0 : p.getSort(),
                            models.stream().filter(m -> p.getCode().equals(m.getProviderCode())).count());
                }).toList();
    }

    /** 新建提供方：code 2~32 位小写字母、数字、下划线、减号，不能重复。 */
    @Transactional
    public LlmProvider createProvider(ProviderForm form) {
        String code = form.code() == null ? "" : form.code().trim();
        if (!code.matches("[a-z0-9_-]{2,32}")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "标识只能用 2~32 位小写字母、数字、下划线、减号，例如 deepseek");
        }
        if (findProvider(code).isPresent()) {
            throw new BizException(ErrorCode.CONFLICT, "已经有标识为 " + code + " 的提供方了");
        }
        LlmProvider row = new LlmProvider();
        row.setCode(code);
        row.setEnabled(true);
        row.setSort(0);
        apply(row, form);
        providerMapper.insert(row);
        reload();
        return row;
    }

    /** 修改提供方（标识不能改）；apiKey 为空表示不改 Key。 */
    @Transactional
    public LlmProvider updateProvider(String code, ProviderForm form) {
        LlmProvider row = getProvider(code);
        apply(row, form);
        row.setUpdatedAt(OffsetDateTime.now());
        providerMapper.updateById(row);
        // apply 里清空的字段 updateById 会跳过，这几个允许清空的单独写
        providerMapper.update(null, new LambdaUpdateWrapper<LlmProvider>().eq(LlmProvider::getId, row.getId())
                .set(LlmProvider::getBaseUrl, row.getBaseUrl())
                .set(LlmProvider::getCompletionsPath, row.getCompletionsPath())
                .set(LlmProvider::getAuthHeader, row.getAuthHeader())
                .set(LlmProvider::getNoThinking, row.getNoThinking())
                .set(LlmProvider::getThinkingStyle, row.getThinkingStyle()));
        reload();
        return row;
    }

    /** 删除提供方：下面还有模型时不让删。 */
    @Transactional
    public LlmProvider deleteProvider(String code) {
        LlmProvider row = getProvider(code);
        long count = modelMapper.selectCount(new LambdaQueryWrapper<LlmModel>().eq(LlmModel::getProviderCode, code));
        if (count > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这个提供方下面还有 " + count + " 个模型，先删掉模型");
        }
        providerMapper.deleteById(row.getId());
        reload();
        return row;
    }

    /** 测试连接要用的型号：指定了就用指定的，否则用这个提供方下排第一的模型。 */
    public ModelSpec testSpec(String code, String model) {
        getProvider(code);
        if (StringUtils.hasText(model)) {
            return new ModelSpec(code, model.trim());
        }
        return modelMapper.selectList(new LambdaQueryWrapper<LlmModel>().eq(LlmModel::getProviderCode, code)
                        .orderByAsc(LlmModel::getSort).orderByAsc(LlmModel::getId)).stream().findFirst()
                .map(m -> new ModelSpec(code, m.getModel()))
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "这个提供方下还没有模型，先加一个或填上型号"));
    }

    // ---------------- 模型 ----------------

    /** 全部模型，按提供方和排序。 */
    public List<LlmModel> listModels() {
        return modelMapper.selectList(new LambdaQueryWrapper<LlmModel>().orderByAsc(LlmModel::getProviderCode)
                .orderByAsc(LlmModel::getSort).orderByAsc(LlmModel::getId));
    }

    /** 新建模型：提供方要存在，同一提供方下型号不能重复。 */
    @Transactional
    public LlmModel createModel(ModelForm form) {
        LlmModel m = new LlmModel();
        m.setProviderCode(getProvider(trim(form.providerCode())).getCode());
        m.setModel(requireText(form.model(), "型号"));
        if (modelMapper.selectCount(new LambdaQueryWrapper<LlmModel>().eq(LlmModel::getProviderCode, m.getProviderCode())
                .eq(LlmModel::getModel, m.getModel())) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "这个提供方下已经有型号 " + m.getModel() + " 了");
        }
        m.setEnabled(true);
        m.setUserSelectable(false);
        m.setSupportsTools(true);
        m.setSort(0);
        m.setThinking(false);
        m.setThinkingLevel("MEDIUM");
        apply(m, form);
        modelMapper.insert(m);
        reload();
        return m;
    }

    /** 修改模型（提供方和型号不能改）；被分配给对话的模型不能停用、不能改成不支持工具。 */
    @Transactional
    public LlmModel updateModel(long id, ModelForm form) {
        LlmModel m = getModel(id);
        apply(m, form);
        Map<String, Long> roles = snapshot().roles();
        if (Objects.equals(roles.get(ChatModelRegistry.SKILL), id) && !Boolean.TRUE.equals(m.getSupportsTools())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这个模型正用于对话，对话要调工具，不能改成不支持工具");
        }
        if (roles.containsValue(id) && !Boolean.TRUE.equals(m.getEnabled())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这个模型正在用（见用途分配），先换成别的模型再停用");
        }
        m.setUpdatedAt(OffsetDateTime.now());
        modelMapper.updateById(m);
        modelMapper.update(null, new LambdaUpdateWrapper<LlmModel>().eq(LlmModel::getId, id)
                .set(LlmModel::getDescription, m.getDescription())
                .set(LlmModel::getPriceInput, m.getPriceInput())
                .set(LlmModel::getPriceCachedInput, m.getPriceCachedInput())
                .set(LlmModel::getPriceOutput, m.getPriceOutput())
                .set(LlmModel::getThinkingBudget, m.getThinkingBudget()));
        reload();
        return m;
    }

    /** 删除模型：正在用于某个用途时不让删；选了它的用户改回默认。 */
    @Transactional
    public LlmModel deleteModel(long id) {
        LlmModel m = getModel(id);
        if (snapshot().roles().containsValue(id)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这个模型正在用（见用途分配），先换成别的模型再删");
        }
        userMapper.update(null, new LambdaUpdateWrapper<User>().eq(User::getPreferredModelId, id)
                .set(User::getPreferredModelId, null));
        modelMapper.deleteById(id);
        reload();
        return m;
    }

    // ---------------- 用途分配 ----------------

    /** 各用途现在分配的模型 id。 */
    public Map<String, Long> roleAssignments() {
        Map<String, Long> out = new LinkedHashMap<>();
        Map<String, Long> roles = snapshot().roles();
        ROLES.forEach(r -> out.put(r, roles.get(r)));
        return out;
    }

    /** 给一个用途分配模型：模型要启用；对话要调工具，只能选支持工具的。 */
    @Transactional
    public LlmModel assignRole(String role, long modelId) {
        if (!ROLES.contains(role)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没有这个用途：" + role);
        }
        LlmModel m = getModel(modelId);
        if (!Boolean.TRUE.equals(m.getEnabled())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这个模型已停用");
        }
        if (ChatModelRegistry.SKILL.equals(role) && !Boolean.TRUE.equals(m.getSupportsTools())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "对话要调工具，这个模型标的是不支持工具，不能用于对话");
        }
        putRole(role, modelId);
        reload();
        return m;
    }

    // ---------------- App 用户选模型 ----------------

    /** 用户在 App 里能选的模型：启用、勾了"App 可选"、支持工具，且提供方启用并填了 Key。 */
    public List<LlmModel> selectableModels() {
        Snapshot s = snapshot();
        return s.models().values().stream()
                .filter(m -> Boolean.TRUE.equals(m.getEnabled()) && Boolean.TRUE.equals(m.getUserSelectable())
                        && Boolean.TRUE.equals(m.getSupportsTools()))
                .filter(m -> {
                    LifeProperties.Provider p = s.providers().get(m.getProviderCode());
                    return p != null && StringUtils.hasText(p.getApiKey());
                })
                .sorted(Comparator.comparing((LlmModel m) -> m.getSort() == null ? 0 : m.getSort())
                        .thenComparing(LlmModel::getId))
                .toList();
    }

    /** 这个用户的模型选择：可选列表、自己选的（已经不可选的当没选）、默认。 */
    public Choices choices(long userId) {
        List<LlmModel> list = selectableModels();
        User user = userMapper.selectById(userId);
        Long preferred = user == null ? null : user.getPreferredModelId();
        Long selected = preferred != null && list.stream().anyMatch(m -> m.getId().equals(preferred)) ? preferred : null;
        return new Choices(list.stream().map(m -> new Choice(m.getId(), m.getDisplayName(), m.getDescription())).toList(),
                selected, snapshot().roles().get(ChatModelRegistry.SKILL));
    }

    /** 保存用户选的对话模型，null 表示用默认；只能选可选列表里的。 */
    public void choose(long userId, Long modelId) {
        if (modelId != null && selectableModels().stream().noneMatch(m -> m.getId().equals(modelId))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这个模型现在不能选了，换一个试试");
        }
        userMapper.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, userId)
                .set(User::getPreferredModelId, modelId).set(User::getUpdatedAt, OffsetDateTime.now()));
    }

    /** 用户自己选的对话模型（"提供方:型号"）；没选或已经不可选时为空，用默认。 */
    public Optional<String> preferredSpec(Long modelId) {
        if (modelId == null) {
            return Optional.empty();
        }
        return selectableModels().stream().filter(m -> m.getId().equals(modelId)).findFirst().map(LlmModel::spec);
    }

    // ---------------- 内部 ----------------

    /** 重新从数据库加载快照，后台每次修改后调用。 */
    public synchronized void reload() {
        Map<String, LifeProperties.Provider> providers = new LinkedHashMap<>();
        for (LlmProvider p : providerMapper.selectList(new LambdaQueryWrapper<LlmProvider>()
                .orderByAsc(LlmProvider::getSort).orderByAsc(LlmProvider::getId))) {
            if (!Boolean.TRUE.equals(p.getEnabled())) {
                continue;
            }
            LifeProperties.Provider c = new LifeProperties.Provider();
            c.setType(p.getType());
            c.setBaseUrl(p.getBaseUrl());
            c.setCompletionsPath(p.getCompletionsPath());
            c.setApiKey(decrypt(p));
            c.setAuthHeader(p.getAuthHeader());
            c.setNoThinking(fromJson(p.getNoThinking()));
            c.setThinkingStyle(p.getThinkingStyle());
            providers.put(p.getCode(), c);
        }
        Map<Long, LlmModel> models = new LinkedHashMap<>();
        modelMapper.selectList(null).forEach(m -> models.put(m.getId(), m));
        Map<String, Long> roles = new LinkedHashMap<>();
        roleMapper.selectList(null).forEach(r -> roles.put(r.getRole(), r.getModelId()));
        snapshot = new Snapshot(providers, models, roles);
    }

    /** 当前快照，还没加载时先加载（例如启动导入之前就有调用）。 */
    private Snapshot snapshot() {
        Snapshot s = snapshot;
        if (s == null) {
            reload();
            s = snapshot;
        }
        return s;
    }

    private void putRole(String role, Long modelId) {
        if (modelId == null) {
            return;
        }
        LlmRole row = new LlmRole();
        row.setRole(role);
        row.setModelId(modelId);
        row.setUpdatedAt(OffsetDateTime.now());
        if (roleMapper.selectById(role) == null) {
            roleMapper.insert(row);
        } else {
            roleMapper.updateById(row);
        }
    }

    /** 把表单内容写到提供方上，并校验类型、地址、请求头和关闭思考参数。 */
    private void apply(LlmProvider row, ProviderForm form) {
        row.setName(requireText(form.name(), "名称"));
        String type = form.type() == null ? "openai" : form.type().trim();
        if (!TYPES.contains(type)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "类型只能是 openai（OpenAI 兼容接口）或 dashscope（百炼原生）");
        }
        row.setType(type);
        row.setBaseUrl(blankToNull(form.baseUrl()));
        if ("openai".equals(type) && row.getBaseUrl() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "OpenAI 兼容接口要填接口地址");
        }
        if (row.getBaseUrl() != null && !row.getBaseUrl().matches("https?://\\S+")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "接口地址要以 http:// 或 https:// 开头");
        }
        row.setCompletionsPath(blankToNull(form.completionsPath()));
        if (row.getCompletionsPath() != null && !row.getCompletionsPath().startsWith("/")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "对话接口路径要以 / 开头，例如 /v1/chat/completions");
        }
        String header = blankToNull(form.authHeader());
        if (header != null && !header.matches("[A-Za-z0-9-]{1,64}")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请求头名只能用字母、数字和减号，例如 api-key");
        }
        row.setAuthHeader(header);
        String noThinking = blankToNull(form.noThinking());
        if (noThinking != null) {
            fromJsonStrict(noThinking);
        }
        row.setNoThinking(noThinking);
        String style = blankToNull(form.thinkingStyle());
        if (style != null && !THINKING_STYLES.contains(style)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "思考参数格式不对");
        }
        if ("CUSTOM".equals(style) && noThinking == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "选了自定义，要填关闭思考时带的参数（JSON）");
        }
        row.setThinkingStyle(style);
        if (StringUtils.hasText(form.apiKey())) {
            row.setApiKeyEnc(cipher.encrypt(form.apiKey().trim()));
        }
        if (form.enabled() != null) {
            row.setEnabled(form.enabled());
        }
        if (form.sort() != null) {
            row.setSort(form.sort());
        }
    }

    /** 把表单内容写到模型上。 */
    private void apply(LlmModel m, ModelForm form) {
        m.setDisplayName(StringUtils.hasText(form.displayName()) ? form.displayName().trim() : m.getModel());
        m.setDescription(blankToNull(form.description()));
        if (form.enabled() != null) {
            m.setEnabled(form.enabled());
        }
        if (form.supportsTools() != null) {
            m.setSupportsTools(form.supportsTools());
        }
        if (form.userSelectable() != null) {
            m.setUserSelectable(form.userSelectable());
        }
        if (Boolean.TRUE.equals(m.getUserSelectable()) && !Boolean.TRUE.equals(m.getSupportsTools())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "对话要调工具，不支持工具的模型不能给 App 用户选");
        }
        for (BigDecimal price : new BigDecimal[]{form.priceInput(), form.priceCachedInput(), form.priceOutput()}) {
            if (price != null && price.signum() < 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "单价不能是负数");
            }
        }
        m.setPriceInput(form.priceInput());
        m.setPriceCachedInput(form.priceCachedInput());
        m.setPriceOutput(form.priceOutput());
        if (form.sort() != null) {
            m.setSort(form.sort());
        }
        if (form.thinking() != null) {
            m.setThinking(form.thinking());
        }
        if (form.thinkingLevel() != null) {
            if (!THINKING_LEVELS.contains(form.thinkingLevel())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "思考深度只能是低、中、高或自定义");
            }
            m.setThinkingLevel(form.thinkingLevel());
        }
        if ("CUSTOM".equals(m.getThinkingLevel())) {
            Integer budget = form.thinkingBudget() != null ? form.thinkingBudget() : m.getThinkingBudget();
            if (budget == null || budget < 128 || budget > 81920) {
                throw new BizException(ErrorCode.BAD_REQUEST, "自定义思考预算要在 128 到 81920 Token 之间");
            }
            m.setThinkingBudget(budget);
        } else {
            m.setThinkingBudget(null);
        }
    }

    private Optional<LlmProvider> findProvider(String code) {
        return Optional.ofNullable(providerMapper.selectOne(
                new LambdaQueryWrapper<LlmProvider>().eq(LlmProvider::getCode, code)));
    }

    /** 按标识取提供方，不存在报 404。 */
    public LlmProvider getProvider(String code) {
        return findProvider(code).orElseThrow(() -> BizException.notFound("模型提供方"));
    }

    /** 按 id 取模型，不存在报 404。 */
    public LlmModel getModel(long id) {
        LlmModel m = modelMapper.selectById(id);
        if (m == null) {
            throw BizException.notFound("模型");
        }
        return m;
    }

    /** 解密 Key；密钥变了解不开时当没有 Key，打警告。 */
    private String decrypt(LlmProvider p) {
        if (p.getApiKeyEnc() == null) {
            return null;
        }
        try {
            return cipher.decrypt(p.getApiKeyEnc());
        } catch (IllegalStateException e) {
            log.warn("模型提供方 {} 的 Key 解密失败，到后台重新填一下：{}", p.getCode(), e.getMessage());
            return null;
        }
    }

    private String parse(String spec, Set<String> providerNames) {
        return ModelSpec.parse(spec, properties.getLlm().getDefaultProvider(), providerNames).id();
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return fromJsonStrict(json);
        } catch (BizException e) {
            return null;
        }
    }

    /** 解析关闭思考参数，不是 JSON 对象时报 400。 */
    private Map<String, Object> fromJsonStrict(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "关闭思考参数要是 JSON 对象，例如 {\"thinking\":{\"type\":\"disabled\"}}");
        }
    }

    private static String tail(String key) {
        return key.length() <= 4 ? "****" : "****" + key.substring(key.length() - 4);
    }

    private static BigDecimal zero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String requireText(String s, String label) {
        if (!StringUtils.hasText(s)) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "不能为空");
        }
        return s.trim();
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
