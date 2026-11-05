package com.lifeassistant.core.pay;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/** t_pay_refund 退款请求的 Mapper。 */
@Mapper
public interface PayRefundMapper extends BaseMapper<PayRefund> {

    /** 按退款请求号查。 */
    @Select("SELECT * FROM t_pay_refund WHERE out_request_no = #{outRequestNo}")
    PayRefund findByOutRequestNo(@Param("outRequestNo") String outRequestNo);

    /** 一笔订单的全部退款请求，新的在前。 */
    @Select("SELECT * FROM t_pay_refund WHERE out_trade_no = #{outTradeNo} ORDER BY id DESC")
    List<PayRefund> listByOrder(@Param("outTradeNo") String outTradeNo);

    /** 占用中的退款金额：成功的加上结果还没确认的，新退款不能让它超过订单金额。 */
    @Select("""
            SELECT COALESCE(SUM(refund_amount), 0) FROM t_pay_refund
            WHERE out_trade_no = #{outTradeNo} AND status IN ('PENDING', 'SUCCESS')
            """)
    BigDecimal sumActive(@Param("outTradeNo") String outTradeNo);

    /** 已经确认成功的退款金额。 */
    @Select("""
            SELECT COALESCE(SUM(refund_amount), 0) FROM t_pay_refund
            WHERE out_trade_no = #{outTradeNo} AND status = 'SUCCESS'
            """)
    BigDecimal sumSucceeded(@Param("outTradeNo") String outTradeNo);

    /** 更新状态和说明；已经成功的退款不会再被改成别的状态。 */
    @Update("""
            UPDATE t_pay_refund SET status = #{status}, message = #{message}, updated_at = now()
            WHERE out_request_no = #{outRequestNo} AND status <> 'SUCCESS'
            """)
    int updateStatus(@Param("outRequestNo") String outRequestNo, @Param("status") String status,
                     @Param("message") String message);
}
