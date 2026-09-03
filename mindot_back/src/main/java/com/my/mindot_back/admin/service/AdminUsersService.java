// 관리자의 회원 기본 정보 조회 기능을 처리하는 Service
package com.my.mindot_back.admin.service;

import com.my.mindot_back.admin.dto.AdminUsersListResponseDto;
import com.my.mindot_back.safety.repository.SafetyEventsRepository;
import com.my.mindot_back.users.repository.UsersRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminUsersService {

    // 회원 기본 정보를 조회하는 Repository
    private final UsersRepository usersRepository;

    // 회원별 안전 신호 횟수 집계 Repository
    private final SafetyEventsRepository safetyEventsRepository;

    // 가입일 최신순으로 회원 기본 정보와 안전 신호 횟수 조회
    @Transactional(readOnly = true)
    public List<AdminUsersListResponseDto> getUsers() {
        Map<Long, Long> safetyEventCountsByUserId =
                safetyEventsRepository.countSafetyEventsByUser()
                        .stream()
                        .collect(Collectors.toMap(
                                SafetyEventsRepository.SafetyEventUserCountProjection::getUserId,
                                SafetyEventsRepository.SafetyEventUserCountProjection::getSafetyEventCount
                        ));

        return usersRepository.findAll(
                        Sort.by(
                                Sort.Direction.DESC,
                                "createdAt"
                        )
                )
                .stream()
                .map(user -> AdminUsersListResponseDto.from(
                        user,
                        safetyEventCountsByUserId.getOrDefault(user.getId(), 0L)
                ))
                .toList();
    }
}
