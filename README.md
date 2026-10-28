# 生活助手

基于 Spring AI Alibaba 的个人生活助手。先给自己用，接口按产品标准设计，以后逐步做成产品。
总体设计见 [docs/blueprint.md](docs/blueprint.md)。

当前阶段：**S0 骨架**。后端已打通登录、会话、记忆、编排图（路由 → 技能，流式输出，断点存库）、
出游技能、日程与提醒推送、日历订阅、管理接口。前端页面是下一步。

## 环境要求

- JDK 21 或更高（Maven 编译目标是 21）
- Maven 3.9+
- Docker（本地数据库、Redis，以及集成测试）

## 本地运行

```bash
# 1. 配置
cp .env.example .env
# 编辑 .env：至少填 LIFE_CRYPTO_KEY（openssl rand -base64 32）、一个模型 Key（默认 DASHSCOPE_API_KEY）、
# LIFE_USERNAME / LIFE_PASSWORD、LIFE_ADMIN_USERNAME / LIFE_ADMIN_PASSWORD

# 2. 起数据库和 Redis
docker compose -f deploy/docker-compose.yml up -d postgres redis

# 3. 启动应用（读取 .env）
set -a && source .env && set +a
mvn -pl life-api -am spring-boot:run
```

启动后：

- 接口调试页面：http://localhost:8080/swagger-ui.html
- 健康检查：http://localhost:8080/actuator/health

## 试一下

```bash
# 登录，拿到 token
curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"你的用户名","password":"你的密码"}'

TOKEN=上一步返回的 token

# 新建会话
curl -s -X POST localhost:8080/api/v1/conversations -H "Authorization: Bearer $TOKEN"

# 发消息（流式返回）
curl -N localhost:8080/api/v1/conversations/会话ID/messages \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"content":"周六想去爬泰山看日出，从济南出发，帮我规划一下"}'

# 设置 Bark 推送并发一条测试
curl -s -X PUT localhost:8080/api/v1/notify/bark -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"deviceKey":"Bark App 里的 key"}'
curl -s -X POST localhost:8080/api/v1/notify/test -H "Authorization: Bearer $TOKEN"
```

### iPhone 日历订阅

1. 创建令牌：`POST /api/v1/tokens`，body `{"name":"iPhone 日历","scope":"CALENDAR_FEED"}`，记下返回的 `plainKey`（只显示一次）
2. iPhone：设置 → 日历 → 账户 → 添加账户 → 其他 → 添加已订阅的日历，
   填 `https://你的域名/api/v1/calendar/feed/<plainKey>.ics`

## 测试

```bash
mvn test
```

集成测试用 Testcontainers 启动真实的 PostgreSQL 和 Redis，需要 Docker 在运行。
模型调用用 Mock 代替，不需要 API Key。

## 评测

路由评测用例在 `eval/cases/`。改 Prompt 或换模型后，用管理员账号调用
`POST /admin/api/v1/eval/routing`（会调用模型，产生少量费用）。

## 项目结构

```
life-common          通用：返回结构、异常、UserCtx
life-core            平台核心：账号、会话、记忆、日程与提醒、推送、天气、编排图、技能注册、审计、评测
life-skill-travel    出游技能：行程规划、爬山计算与风险规则、行程版本
life-skill-schedule  日程技能（对话入口）
life-api             启动模块：接口、Sa-Token、配置
eval/                评测用例
deploy/              docker compose、Dockerfile
docs/                设计文档
```

## 约定

- 业务代码只通过 `AuthFacade` / `UserCtx` 获取身份；AI 链路（图节点、工具）里不调用 `StpUtil`，
  身份通过 Graph 状态和 `ToolContext` 传递。
- 数据库迁移：每个模块一个目录，版本号区间 core `V1_x`、出游 `V2_x`、日程 `V3_x`、健身 `V4_x`、
  记账 `V5_x`、内容总结 `V6_x`、管理后台 `V9_x`。新增模块时记得加到 `spring.flyway.locations`。
- 模型写在配置里（`LIFE_MODEL_*`，写法"提供方:型号"，如 `deepseek:deepseek-chat`），不写死在代码里；
  业务代码通过 `ChatModelRegistry` 取模型，不直接用某个厂商的类。换模型后跑一遍评测。
- 密钥只放 `.env`，不提交。
