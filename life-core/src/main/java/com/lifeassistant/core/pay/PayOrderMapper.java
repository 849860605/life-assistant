package com.lifeassistant.core.pay;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** t_pay_order 商户订单的 Mapper：状态只能单向变化，更新语句都带条件，重复执行不会出错。 */
@Mapper
public interface PayOrderMapper extends BaseMapper<PayOrder> {

    /** 按商户订单号查。 */
    @Select("SELECT * FROM t_pay_order WHERE out_trade_no = #{outTradeNo}")
    PayOrder findByOutTradeNo(@Param("outTradeNo") String outTradeNo);

    /** 按商户订单号查并锁行，算累计退款时用，必须在事务里调用。 */
    @Select("SELECT * FROM t_pay_order WHERE out_trade_no = #{outTradeNo} FOR UPDATE")
    PayOrder lockByOutTradeNo(@Param("outTradeNo") String outTradeNo);

    /** 标记已支付（幂等）：已经是 PAID 的不再改，返回实际更新的行数。 */
    @Update("""
            UPDATE t_pay_order SET status = 'PAID', trade_no = #{tradeNo}, paid_at = now(), updated_at = now()
            WHERE out_trade_no = #{outTradeNo} AND status <> 'PAID'
            """)
    int markPaid(@Param("outTradeNo") String outTradeNo, @Param("tradeNo") String tradeNo);

    /** 标记已关闭：只改待支付的订单，已支付的不受影响。 */
    @Update("""
            UPDATE t_pay_order SET status = 'CLOSED', updated_at = now()
            WHERE out_trade_no = #{outTradeNo} AND status = 'WAIT_PAY'
            """)
    int markClosed(@Param("outTradeNo") String outTradeNo);
}
