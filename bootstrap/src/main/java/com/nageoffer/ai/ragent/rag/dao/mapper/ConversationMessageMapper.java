/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface ConversationMessageMapper extends BaseMapper<ConversationMessageDO> {

    @Select("select left(um.content, 100) as question, "
            + "count(*) as frequency, "
            + "max(um.create_time) as lastSeen "
            + "from t_message um "
            + "where um.role = 'user' "
            + "and um.deleted = 0 "
            + "and um.create_time >= #{start} "
            + "and um.create_time < #{end} "
            + "and exists ("
            + "  select 1 from t_message am "
            + "  where am.conversation_id = um.conversation_id "
            + "  and am.role = 'assistant' "
            + "  and am.content = #{noDocReply} "
            + "  and am.deleted = 0 "
            + "  and am.create_time > um.create_time"
            + ") "
            + "group by left(um.content, 100) "
            + "order by frequency desc "
            + "limit #{limit}")
    List<Map<String, Object>> findBlindSpots(@Param("start") Date start,
                                             @Param("end") Date end,
                                             @Param("noDocReply") String noDocReply,
                                             @Param("limit") int limit);

    @Select("select left(um.content, 100) as question, "
            + "count(*) as cnt, "
            + "sum(case when coalesce(am_next.content, '') != #{noDocReply} then 1 else 0 end) as hits, "
            + "count(f.id) as feedbackCount, "
            + "sum(case when f.vote = 1 then 1 else 0 end) as thumbsUp "
            + "from t_message um "
            + "left join lateral ("
            + "  select am.content, am.id as am_id "
            + "  from t_message am "
            + "  where am.conversation_id = um.conversation_id "
            + "  and am.role = 'assistant' "
            + "  and am.deleted = 0 "
            + "  and am.create_time > um.create_time "
            + "  order by am.create_time asc "
            + "  limit 1"
            + ") am_next on true "
            + "left join t_message_feedback f on f.message_id = am_next.am_id and f.deleted = 0 "
            + "where um.role = 'user' "
            + "and um.deleted = 0 "
            + "and um.create_time >= #{start} "
            + "and um.create_time < #{end} "
            + "group by left(um.content, 100) "
            + "order by cnt desc "
            + "limit #{limit}")
    List<Map<String, Object>> findTopQuestions(@Param("start") Date start,
                                               @Param("end") Date end,
                                               @Param("limit") int limit,
                                               @Param("noDocReply") String noDocReply);
}
