package com.lifeassistant.core.pay;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** t_pay_refund 表实体：一次退款请求，status 为 PENDING / SUCCESS / FAILED。 */
@Data
@TableName("t_pay_refund")
public class PayRefund {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String outTradeNo;
    private String outRequestNo;
    private BigDecimal refundAmount;
    private String reason;
    private String status;
    private String message;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
