# 可接入的开放能力调研

生活助手能接哪些第三方 App 的官方 API 或 MCP，重点看对个人开发者是否开放。

- 调研日期：2026-10-29
- 资料来源：各家官网和公开文档、社区汇总（awesome-china-mcp，核对日期 2026-10-10）、新闻报道
- 个人资格、免费额度、价格以各家控制台为准，接入时逐个实测后更新本文档

状态说明：✅ 已接入 · 🔑 代码就绪，等 Key · 📋 计划接入 · ⏸ 暂不接 · ❌ 没有可用的官方能力

---

## 1. 结论

- **出游类最充分**：酒店价格已经能多平台比价（RollingGo、携程、美团、飞猪，都是官方给 AI 助手的接口，个人可申请）；航班（飞常准）、打车（滴滴）也有官方远程 MCP。
- **吃喝类基本空白**：美团、饿了么、大众点评都没有官方开放；麦当劳、瑞幸有官方 MCP，但用途窄。
- **健身健康、记账、内容（小红书）没有官方能力**：健身数据走 Apple 健康（快捷指令同步），记账走截图或账单导入，小红书走搜索引擎摘要加用户分享。
- 远程 MCP 统一通过 `McpServerRegistry` 接入，大多只需要加配置（见第 5 节）。

---

## 2. 已接入和计划接入

| 优先级 | 服务 | 状态 | 方式 | 个人能否使用 | 带来的能力 |
|---|---|---|---|---|---|
| — | 高德地图 | ✅ | 远程 MCP（SSE，Key 在网址里） | ✅ 免费额度 | 地点、路线、交通耗时、周边搜索、简单天气 |
| — | 和风天气 | 🔑 | REST 接口 | ✅ 免费额度 | 逐日和逐小时预报、日出日落、气象预警；没配时用高德天气兜底 |
| — | 博查搜索 | 🔑 | REST 接口 | ✅ 按次付费，新用户送 1000 次 | 搜攻略，小红书笔记优先（只有标题、摘要和链接） |
| 1 | **RollingGo 酒店**（道旅） | ✅ | 远程 MCP（Streamable HTTP，`Authorization: Bearer`） | ✅ 免费、不限调用量（官网说法） | 按地点和日期搜酒店的实时价格、房型、取消政策（它的机票接口 /mcp/flight 实测 404，不可用） |
| — | **携程问道**（TripAI） | ✅ | HTTP 接口，自然语言查询，返回 Markdown | ✅ 个人可申请 Key，不填也能低频匿名用 | 酒店最低参考价、全房型清单、带日期的预订链接；**机票**（航班、价格、预订链接） |
| — | **美团酒旅 ht-ai** | ✅ | HTTP 接口（`Authorization: <token>`），返回 Markdown | ✅ 个人可申请 Token | 酒店房型级价格、取消政策、预订短链（约价，可能含团购价）；**机票**（航班、准点率、价格、预订短链） |
| — | **飞猪 FlyAI** | ✅ | 官方命令行工具 `@fly-ai/flyai-cli`（签名在工具里），后端调用它 | ✅ 个人可申请 Key | 酒店级明码价、**机票**（结构化航班数据，含中转）、火车票；服务器需要 Node.js |
| 2 | **途牛 MCP 开放平台** | 📋 | 远程 MCP（`apiKey` 请求头） | ⚠️ 官方没写个人能否申请，需注册确认 | 酒店、机票、门票（2026-03 上线首批） |
| 3 | **滴滴出行** | 📋 | 远程 MCP（Streamable HTTP，Key 在网址里） | ✅ 个人账号申请"个人 MCP Key"，有沙箱 | 打车估价、叫车、查行程、取消；叫车前必须用户确认，付款在滴滴 App |
| 4 | **飞常准** | 📋 | 远程 MCP（Streamable HTTP，`X-API-Key` 请求头）或 npm 包 | ✅ 邮箱注册，不要企业资质；送 ¥50 试用额度（另一说法 100 次） | 航班动态、延误、准点率，可接进日程提醒 |
| 5 | 麦当劳中国 | 📋 | 远程 MCP（Bearer，需授权登录） | ✅ | 菜单、营养成分、附近门店，健身技能查餐食热量 |

申请地址：

| 服务 | 地址 | `.env` 变量 |
|---|---|---|
| 和风天气 | https://console.qweather.com | `QWEATHER_API_HOST`、`QWEATHER_API_KEY` |
| 博查 | https://open.bochaai.com | `BOCHA_API_KEY` |
| RollingGo | https://travelportal-partner-center.dida.com/register?lang=zh | `ROLLINGGO_API_KEY` |
| 途牛 | 途牛 MCP 开放平台（open.tuniu.cn） | 接入时新增 |
| 滴滴 | 滴滴开放平台，成为开发者后在控制台激活"个人 MCP Key" | 接入时新增 |
| 飞常准 | https://mcp.variflight.com/api-keys | 接入时新增 |

---

## 3. 有官方能力但暂不接

| 服务 | 方式 | 不接的原因 |
|---|---|---|
| 腾讯地图、百度地图 | 远程 MCP | 已有高德，能力重复 |
| 彩云天气 | 接口（社区 MCP） | 已有和风和高德；以后要分钟级降水预报再接 |
| 瑞幸咖啡 | 远程 MCP，开放平台申请 | 用途窄，以后做"帮我点杯咖啡"再接 |
| 支付宝"支付 MCP" | 蚂蚁百宝箱、魔搭 | 给**商家收款**用的，个人付款用不上 |
| 快递 100 | 官方 MCP | 要企业账号 |
| 飞书、语雀、腾讯文档、印象笔记、flomo | 官方或社区 MCP | 做知识库或笔记同步时再考虑 |
| PushPlus、Server 酱 | 推送 | 已有 Bark |

---

## 4. 没有官方开放的能力

| 场景 | 情况 | 替代方案 |
|---|---|---|
| 美团外卖、饿了么、淘宝闪购 | 没有官方 MCP，只有社区的半成品 | 延后：跳转链接，以后考虑安卓 GUI Agent（见蓝图"延后"部分） |
| 大众点评 | 没有官方开放 | 高德评分 + 博查搜攻略 |
| 小红书 | 没有官方 MCP；社区 MCP 是用登录状态自动操作浏览器 | 博查搜索摘要（限定 xiaohongshu.com），加上用户分享链接或截图总结（S1 内容总结） |
| 12306 | 没有官方 MCP；社区项目调网页查询接口，只能查余票不能下单 | 暂不接；需要时给 12306 跳转链接 |
| 去哪儿 | 没找到对外开放的 AI 查询接口 | 携程问道（同属携程集团） |
| 同程 | 有官方"程心"能力，Key 在同程旅行 App 里搜"程心激活码"申领 | 待申请后接入 |
| 健身健康（Keep、华为、小米） | 没有 | iOS 快捷指令同步 Apple 健康（S1，`HEALTH_SYNC` 令牌） |
| 记账、银行流水 | 没有 | 小票截图识别、微信或支付宝账单文件导入（S1） |

---

## 5. 怎么接一个新的远程 MCP

远程 MCP 由 `life-core` 的 `McpServerRegistry` 统一管理：

- 第一次用到时才连接，连上后缓存；
- 没配 Key 的服务跳过；
- 连接失败只记日志，5 分钟后再试，不影响启动和对话。

**第一步**：在 `application.yml` 的 `life.mcp` 下加一段，Key 从环境变量读。

```yaml
life:
  mcp:
    variflight:
      url: https://<飞常准远程 MCP 地址>
      endpoint: /mcp
      api-key: ${VARIFLIGHT_API_KEY:}
      auth-header: X-API-Key   # 默认 Authorization
      auth-prefix: ""          # 默认 "Bearer "
```

**第二步**：在需要它的技能的 `toolCallbacks()` 里加上 `mcpServers.tools("variflight")`。

**第三步**：在技能提示词里说明什么时候用这些工具，以及数据来源要怎么标注。

需要先做一处小改动：滴滴、腾讯地图把 Key 放在网址里，`McpServerRegistry` 要支持在 `url` 里用 `{apiKey}` 占位、不加请求头。接入滴滴时一起改。

接入原则：

- 只接官方或官方授权的数据源，不爬取、不逆向；
- 下单、叫车这类有后果的动作，执行前必须用户确认；付款永远由用户在对应 App 里完成；
- 价格、余票这类数据在回答里注明来源，比如"价格来自 RollingGo"。

---

## 6. 参考资料

- [awesome-china-mcp（国内 MCP 汇总）](https://github.com/shcdwz1234/awesome-china-mcp)
- [RollingGo 酒店 MCP](https://github.com/RollingGo-AI/rollinggo-hotel-mcp)
- [RollingGo Hotel MCP Global](https://github.com/RollingGo-AI/RollingGo-Hotel-MCP-Global)
- [途牛 MCP 开放平台正式上线（新浪财经）](https://finance.sina.com.cn/roll/2026-03-10/doc-inhqnmhx6391543.shtml)
- [途牛开放 API](http://open.tuniu.cn/openAPI.html)
- [滴滴出行 MCP（腾讯云开发者）](https://cloud.tencent.com/developer/mcp/server/11746)
- [variflight-mcp（GitHub）](https://github.com/variflight/variflight-mcp)
- [VariFlight MCP Server 介绍](https://dataworks.variflight.com/blog/smarter-travel-with-ai-introducing-variflight-s-aviation-mcp-server/)
- [飞猪 FlyAI 开放平台](https://flyai.open.fliggy.com/)
- [meituan-travel-mcp（基于飞猪 FlyAI）](https://github.com/luffy666code/meituan-travel-mcp)
- [支付宝支付 MCP Server（量子位）](https://www.qbitai.com/2025/04/273734.html)
- [百炼广场 MCP 服务](https://docs.agent.bailian.aliyun.com/zh/mcp/official-services)
- [mcp-server-12306](https://mcp.so/servers/mcp-server-12306)
- [博查搜索 API 产品文档](https://netmarket.oss-cn-hangzhou.aliyuncs.com/f0f21b3fc270430796edaa67e49fe203.%E5%8D%9A%E6%9F%A5%E6%90%9C%E7%B4%A2api%E4%BA%A7%E5%93%81%E6%9C%8D%E5%8A%A1%E6%96%87%E6%A1%A3v1.0.0.pdf)
- [百度千帆搜索 API](https://cloud.baidu.com/doc/qianfan-api/s/Wmbq4z7e5)
- [智谱联网搜索](https://docs.bigmodel.cn/cn/guide/tools/web-search)
- [小红书 robots.txt](https://www.xiaohongshu.com/robots.txt)
- [美团技术服务合作中心](https://developer.meituan.com/)
