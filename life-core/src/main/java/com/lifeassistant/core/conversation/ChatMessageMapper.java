package com.lifeassistant.core.conversation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** t_chat_message 的 Mapper。 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

    /** 取会话最近 limit 条消息，按时间正序返回。 */
    @Select("""
            SELECT * FROM (
                SELECT * FROM t_chat_message WHERE conversation_id = #{conversationId}
                ORDER BY id DESC LIMIT #{limit}
            ) t ORDER BY id
            """)
    List<ChatMessageEntity> selectRecent(String conversationId, int limit);

    /** 给本轮的助手消息（最后一条用户消息之后的最后一条助手消息）写 metadata，返回更新行数；本轮没有助手消息时为 0。 */
    @Update("""
            UPDATE t_chat_message SET metadata = CAST(#{json} AS jsonb)
            WHERE id = (SELECT max(id) FROM t_chat_message WHERE conversation_id = #{conversationId} AND role = 'ASSISTANT')
              AND id > (SELECT coalesce(max(id), 0) FROM t_chat_message WHERE conversation_id = #{conversationId} AND role = 'USER')
            """)
    int attachToLastAnswer(String conversationId, String json);
}
