// Redis에 소셜 신규 가입 티켓을 저장·조회·삭제하는 Repository
package com.my.mindot_back.redis.repository;

import com.my.mindot_back.redis.entity.SocialSignupTicket;
import org.springframework.data.repository.CrudRepository;

public interface SocialSignupTicketRepository
        extends CrudRepository<SocialSignupTicket, String> {
}