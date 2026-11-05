package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.pay.AlipayPayService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 支付宝回调（不需要登录，靠验签保证来源）：异步通知、同步回跳结果页、结果页查询支付状态。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/pay/alipay")
@RequiredArgsConstructor
public class AlipayCallbackController {

    private final AlipayPayService payService;

    /** 结果页上“回到星络”的链接，默认是 H5 的会员页 */
    @Value("${life.pay.alipay.app-page:/#/pages/member/member}")
    private String appPage;

    /**
     * POST /api/v1/pay/alipay/notify：支付宝异步通知（表单格式）。处理成功回纯文本 success，
     * 验签、业务校验失败或异常都回 fail，支付宝会按策略重发。
     */
    @PostMapping(value = "/notify", produces = "text/plain;charset=UTF-8")
    public String notify(HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((name, values) -> params.put(name, String.join(",", values)));
        try {
            return payService.handleNotify(params) ? "success" : "fail";
        } catch (Exception e) {
            log.warn("处理支付宝异步通知出错 out_trade_no={} notify_id={}：{}", params.get("out_trade_no"),
                    params.get("notify_id"), e.getMessage());
            return "fail";
        }
    }

    /**
     * GET /api/v1/pay/alipay/return：付款后浏览器回到的结果页。页面先显示“正在确认支付结果”，
     * 再把回跳参数交给 /return/status 验签并由服务端查询支付宝，展示最终结果；没带参数时显示中性提示。
     */
    @GetMapping(value = "/return", produces = "text/html;charset=UTF-8")
    public String returnPage() {
        return RETURN_PAGE.replace("{{APP_PAGE}}", HtmlUtils.htmlEscape(appPage));
    }

    /** POST /api/v1/pay/alipay/return/status：结果页用回跳参数确认支付状态（参数验签，状态以服务端查询为准）。 */
    @PostMapping("/return/status")
    public ApiResponse<AlipayPayService.ReturnView> returnStatus(@RequestBody(required = false) Map<String, String> params) {
        return ApiResponse.ok(payService.confirmReturn(params == null ? Map.of() : params));
    }

    /** 结果页：纯静态 HTML，状态由脚本向 /return/status 查询后填入，回跳参数只作为定位订单的依据。 */
    private static final String RETURN_PAGE = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>支付结果 - 星络</title>
            <style>
              body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", sans-serif; background: #f5f7fb; color: #1e293b; }
              .card { max-width: 420px; margin: 12vh auto 0; background: #fff; border-radius: 16px; padding: 28px 24px; box-shadow: 0 6px 24px rgba(15, 23, 42, .08); text-align: center; }
              h1 { font-size: 20px; margin: 0 0 12px; }
              .state { font-size: 16px; margin: 8px 0; }
              .muted { color: #64748b; font-size: 13px; line-height: 1.6; }
              .ok { color: #16a34a; } .warn { color: #c2410c; }
              a.btn { display: inline-block; margin-top: 18px; padding: 10px 22px; border-radius: 10px; background: #1677ff; color: #fff; text-decoration: none; }
              button { margin-top: 12px; padding: 8px 18px; border-radius: 10px; border: 1px solid #cbd5e1; background: #fff; cursor: pointer; }
            </style>
            </head>
            <body>
            <div class="card">
              <h1>支付结果</h1>
              <div id="state" class="state">正在确认支付结果…</div>
              <div id="detail" class="muted"></div>
              <div class="muted">最终结果以支付宝通知和服务端查询为准</div>
              <button id="retry" type="button" hidden>重新查询</button>
              <div><a class="btn" href="{{APP_PAGE}}">回到星络</a></div>
            </div>
            <script>
            (function () {
              var labels = {
                PAID: ["支付成功", "ok"],
                PENDING_PAYMENT: ["还没有付款完成", "warn"],
                CLOSED: ["订单已关闭", "warn"],
                UNKNOWN: ["暂时无法确认支付结果", "warn"],
                INVALID: ["无法确认这笔支付", "warn"],
                NONE: ["没有需要确认的订单", ""]
              };
              var stateEl = document.getElementById("state");
              var detailEl = document.getElementById("detail");
              var retry = document.getElementById("retry");
              var params = {};
              new URLSearchParams(window.location.search).forEach(function (v, k) { params[k] = v; });
              function show(view) {
                var label = labels[view.state] || labels.UNKNOWN;
                stateEl.textContent = label[0];
                stateEl.className = "state " + label[1];
                var lines = [];
                if (view.subject) lines.push(view.subject + (view.totalAmount ? "，" + view.totalAmount + " 元" : ""));
                if (view.outTradeNo) lines.push("订单号 " + view.outTradeNo);
                if (view.message) lines.push(view.message);
                detailEl.textContent = lines.join("　");
                retry.hidden = !(view.state === "PENDING_PAYMENT" || view.state === "UNKNOWN");
              }
              function load() {
                stateEl.textContent = "正在确认支付结果…";
                stateEl.className = "state";
                fetch("return/status", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(params) })
                  .then(function (r) { return r.json(); })
                  .then(function (body) {
                    if (body && body.code === 0 && body.data) show(body.data);
                    else show({ state: "UNKNOWN", message: (body && body.message) || "查询失败" });
                  })
                  .catch(function () { show({ state: "UNKNOWN", message: "网络异常，请稍后重新查询" }); });
              }
              retry.addEventListener("click", load);
              load();
            })();
            </script>
            </body>
            </html>
            """;
}
