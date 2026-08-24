package com.my.mindot_back.redis.repository;

import com.my.mindot_back.redis.entity.RefreshToken;
import org.springframework.data.repository.CrudRepository;

public interface RefreshTokenRepository extends CrudRepository<RefreshToken, String>{
}
