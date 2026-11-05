# 星络 Xinglo

基于 Spring AI Alibaba 的个人生活助手，AI 叫"星络"。先给自己用，接口按产品标准设计，逐步做成产品。

文档：
- 总体设计：[docs/blueprint.md](docs/blueprint.md)
- 可接入的第三方能力：[docs/open-capabilities.md](docs/open-capabilities.md)
- 页面改版计划：[docs/ui-plan.md](docs/ui-plan.md)
- 全流程走查：[docs/walkthrough-2026-11.md](docs/walkthrough-2026-11.md)
- 部署：[deploy/README.md](deploy/README.md)

## 当前阶段

**MVP 已上线**，部署在阿里云服务器上。开放了邮箱注册，目前还是 http，备案下来后改 https。

App（H5，手机和电脑都能用）：
- **对话**：以和星络对话为中心，流式回答，显示实时进度。回答过程中可以随时补充，星络边做边调整。
- **出游**：
  - 规划流程：需求卡（可改、可跳过）→ 三套方案 → 完整行程，对话里能局部修改行程，改了能撤销。
  - 查价分三层：规划时快速查价；行程保存后，后台给选中的酒店和航班补全各平台价格；点"换"才完整比价。
  - 机票、酒店、门票、美食可以单独查。
- **日程**：对话里说一句就能加，到点 Bark 推送提醒，能订阅到 iPhone 日历。首页的"今日安排"会算建议几点出发，点一下用高德导航或打车。
- **账号**：
  - 邮箱注册，用户名或邮箱都能登录；
  - 忘记密码时用邮箱重置；
  - 在 App 里修改密码；
  - 能自己切换对话用的模型。

管理后台（`/admin/`）：
- 管理员和 App 用户管理，可以开关注册；
- 总览和操作日志；
- 模型配置：增删提供方和模型，型号从提供方拉取；
- 第三方平台：开关、填 Key、测试连通。

约束：
- 下单和付款永远由用户自己在对应平台确认，这里只给链接，打车只打开高德。
- 数据只用官方或授权的接口。

## 环境要求

- JDK 21 或更高（Maven 编译目标是 21）
- Maven 3.9+
- Node.js 18+（前端和管理后台）
- Docker（本地数据库、Redis，以及集成测试）

## 本地运行

```bash
# 1. 配置
cp .env.example .env
# 编辑 .env，至少填：
#   LIFE_CRYPTO_KEY（openssl rand -base64 32）
#   一个模型 Key（如 DEEPSEEK_API_KEY）
#   LIFE_USERNAME / LIFE_PASSWORD
#   LIFE_ADMIN_USERNAME / LIFE_ADMIN_PASSWORD
# 要邮箱注册再填 LIFE_MAIL_*

# 2. 起数据库和 Redis
docker compose -f deploy/docker-compose.yml up -d postgres redis

# 3. 后端（读取 .env）。前端开发服务默认转发到 8081
set -a && source .env && set +a
SERVER_PORT=8081 mvn -pl life-api -am spring-boot:run

# 4. App 前端：http://localhost:5173
cd life-web && npm install && npm run dev:h5

# 5. 管理后台：http://localhost:5174/admin/
cd life-admin && npm install && ADMIN_API=http://localhost:8081 npx vite
```

启动后：
- 健康检查：http://localhost:8081/actuator/health
- 接口调试页面：http://localhost:8081/swagger-ui.html（需要在 `.env` 里设 `LIFE_API_DOCS=true`，线上保持关闭）

### 模型和第三方平台的配置

- **模型**：第一次启动时，`.env` 里的模型配置（`LIFE_MODEL_*` 和各家 Key）会导入数据库，之后在管理后台的"模型配置"里改。
- **第三方平台**：后台"第三方平台"里填了的用后台的，没填的用 `.env` 里的，停用了就当没配 Key。
- 两处改完都马上生效，不用重启。
- 业务代码通过 `ChatModelRegistry` 取模型，不直接用某个厂商的类。

## 试一下

```bash
# 登录，拿到 token（用户名或邮箱都可以）
curl -s localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"你的用户名","password":"你的密码"}'

TOKEN=上一步返回的 token

# 新建会话
curl -s -X POST localhost:8081/api/v1/conversations -H "Authorization: Bearer $TOKEN"

# 发消息（流式返回）
curl -N localhost:8081/api/v1/conversations/会话ID/messages \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"content":"周六想去爬泰山看日出，从济南出发，帮我规划一下"}'

# 设置 Bark 推送并发一条测试
curl -s -X PUT localhost:8081/api/v1/notify/bark -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"deviceKey":"Bark App 里的 key"}'
curl -s -X POST localhost:8081/api/v1/notify/test -H "Authorization: Bearer $TOKEN"
```

### iPhone 日历订阅

1. 创建令牌：`POST /api/v1/tokens`，body `{"name":"iPhone 日历","scope":"CALENDAR_FEED"}`，记下返回的 `plainKey`（只显示一次）
2. iPhone：设置 → 日历 → 账户 → 添加账户 → 其他 → 添加已订阅的日历，
   填 `http(s)://你的域名/api/v1/calendar/feed/<plainKey>.ics`

## 测试

```bash
mvn test                              # 后端：单元测试 + 集成测试
cd life-web && npm run type-check     # App 前端类型检查
cd life-admin && npm run build        # 管理后台类型检查并打包
```

- 集成测试用 Testcontainers 启动真实的 PostgreSQL 和 Redis，需要 Docker 在运行。
- 模型调用用 Mock 代替，不需要 API Key。

## 评测

- 路由评测用例在 `eval/cases/`。
- 改 Prompt 或换模型后，用管理员账号调用 `POST /admin/api/v1/eval/routing`。这个接口会调用模型，产生少量费用。

## 部署

```bash
deploy/deploy.sh
```

在服务器项目目录运行，一键构建并启动前端、后端、PostgreSQL、Redis。服务器的 `.env` 单独维护，不随代码同步。详见 [deploy/README.md](deploy/README.md)。

## 项目结构

```
life-common          通用：返回结构、异常、UserCtx
life-core            平台核心：账号与邮箱验证码、会话、记忆、日程与提醒、推送、天气、编排图、技能注册、
                     模型与第三方平台配置、审计、评测
life-skill-travel    出游技能：行程规划与局部修改、机票酒店门票比价、后台补价、爬山计算与风险规则、行程版本
life-skill-schedule  日程技能（对话入口）
life-skill-food      美食技能
life-api             启动模块：App 接口、管理后台接口、Sa-Token、配置
life-web             App 前端：uni-app（Vue 3 + TS），H5
life-admin           管理后台：Vue 3 + Vite + Element Plus，部署在 /admin/
eval/                评测用例
deploy/              docker compose、Dockerfile、nginx、部署脚本
tools/               辅助脚本（字体下载等）
docs/                设计文档
```

## 约定

- **身份**：业务代码只通过 `AuthFacade` / `UserCtx` 获取身份。AI 链路（图节点、工具）里不调用 `StpUtil`，身份通过 Graph 状态和 `ToolContext` 传递。
- **数据库迁移**：
  - 每个模块一个目录，版本号区间：core `V1_x`、出游 `V2_x`、日程 `V3_x`、健身 `V4_x`、记账 `V5_x`、内容总结 `V6_x`、管理后台 `V9_x`（放在 core 目录下）。
  - 新增模块时记得加到 `spring.flyway.locations`。
- **模型**：模型不写死在代码里，换模型后跑一遍评测。
- **命名**：界面和提示词里 AI 一律叫"星络"，不叫"AI 助手"。
- **密钥**：只放 `.env`，不提交。
