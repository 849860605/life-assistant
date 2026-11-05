package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.pay.AlipayPayService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 支付宝网页支付接口（需要登录）：买会员下单拿支付表单、订单列表和详情、主动查询、关单。
 * 金额由服务端按商品定；退款只在后台做。
 */
@RestController
@RequestMapping("/api/v1/pay/alipay")
@RequiredArgsConstructor
public class AlipayPayController {

    private final AuthFacade authFacade;
    private final AlipayPayService payService;

    /** POST /api/v1/pay/alipay/orders：买一个月会员，返回订单号和支付表单 HTML，前端渲染后自动提交跳到支付宝收银台。 */
    @PostMapping("/orders")
    public ApiResponse<AlipayPayService.CreatedOrder> create() {
        return ApiResponse.ok(payService.createMemberOrder(authFacade.current().userId()));
    }

    /** GET /api/v1/pay/alipay/orders：我最近的 20 笔订单。 */
    @GetMapping("/orders")
    public ApiResponse<List<AlipayPayService.OrderView>> list() {
        return ApiResponse.ok(payService.listOrders(authFacade.current().userId()));
    }

    /** GET /api/v1/pay/alipay/orders/{outTradeNo}：订单详情（本地记录）。 */
    @GetMapping("/orders/{outTradeNo}")
    public ApiResponse<AlipayPayService.OrderView> get(@PathVariable String outTradeNo) {
        return ApiResponse.ok(payService.getOrder(authFacade.current().userId(), outTradeNo));
    }

    /** POST /api/v1/pay/alipay/orders/{outTradeNo}/query：向支付宝查询并同步订单状态，没收到通知时用它兜底。 */
    @PostMapping("/orders/{outTradeNo}/query")
    public ApiResponse<AlipayPayService.QueryView> query(@PathVariable String outTradeNo) {
        return ApiResponse.ok(payService.queryOrder(authFacade.current().userId(), outTradeNo));
    }

    /** POST /api/v1/pay/alipay/orders/{outTradeNo}/close：关闭待支付订单。 */
    @PostMapping("/orders/{outTradeNo}/close")
    public ApiResponse<AlipayPayService.OrderView> close(@PathVariable String outTradeNo) {
        return ApiResponse.ok(payService.closeOrder(authFacade.current().userId(), outTradeNo));
    }
}
