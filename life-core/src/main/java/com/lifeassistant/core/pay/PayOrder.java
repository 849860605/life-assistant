package com.lifeassistant.core.pay;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** t_pay_order 表实体：一笔支付宝网页支付的商户订单，status 为 WAIT_PAY / PAID / CLOSED；productCode 是买的商品（如 MEMBER_MONTH）。 */
@Data
@TableName("t_pay_order")
public class PayOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String outTradeNo;
    private String appId;
    private String productCode;
    private String subject;
    private BigDecimal totalAmount;
    private String status;
    private String tradeNo;
    private OffsetDateTime paidAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
