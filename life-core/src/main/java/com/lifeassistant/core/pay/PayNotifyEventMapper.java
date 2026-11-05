package com.lifeassistant.core.pay;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** t_pay_notify_event 异步通知幂等表的 Mapper。 */
@Mapper
public interface PayNotifyEventMapper {

    /** 记一条通知；同一 notify_id 已经有了就什么都不做，返回 0 表示是重复通知。params 是 JSON 字符串。 */
    @Insert("""
            INSERT INTO t_pay_notify_event (notify_id, out_trade_no, trade_no, trade_status, params)
            VALUES (#{notifyId}, #{outTradeNo}, #{tradeNo}, #{tradeStatus}, #{params})
            ON CONFLICT (notify_id) DO NOTHING
            """)
    int insertOnce(@Param("notifyId") String notifyId, @Param("outTradeNo") String outTradeNo,
                   @Param("tradeNo") String tradeNo, @Param("tradeStatus") String tradeStatus,
                   @Param("params") String params);
}
