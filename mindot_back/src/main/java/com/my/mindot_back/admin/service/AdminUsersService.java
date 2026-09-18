// 관리자의 회원 기본 정보 조회 기능을 처리하는 Service
package com.my.mindot_back.admin.service;

import com.my.mindot_back.admin.dto.AdminUsersListResponseDto;
import com.my.mindot_back.admin.dto.AdminUsersPageResponseDto;
import com.my.mindot_back.safety.repository.SafetyEventsRepository;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

    // 가입일 최신순으로 회원 기본 정보와 안전 신호 횟수를 페이징 조회
    @Transactional(readOnly = true)
    public AdminUsersPageResponseDto getUsers(int page, int size) {
        if (page < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "페이지 번호는 0 이상이어야 합니다."
            );
        }

        if (size < 1 || size > 50) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "페이지 크기는 1 이상 50 이하이어야 합니다."
            );
        }

        Page<Users> usersPage =
                usersRepository.findAll(
                        PageRequest.of(
                                page,
                                size,
                                Sort.by(Sort.Direction.DESC, "createdAt")
                                        .and(Sort.by(Sort.Direction.DESC, "id"))
                        )
                );

        List<Long> userIds = usersPage.getContent()
                .stream()
                .map(user -> user.getId())
                .toList();

        Map<Long, Long> safetyEventCountsByUserId = userIds.isEmpty()
                ? Map.of()
                : safetyEventsRepository.countSafetyEventsByUserIds(userIds)
                  .stream()
                  .collect(Collectors.toMap(
                          SafetyEventsRepository.SafetyEventUserCountProjection::getUserId,
                          SafetyEventsRepository.SafetyEventUserCountProjection::getSafetyEventCount
                  ));

        return AdminUsersPageResponseDto.from(
                usersPage.map(user -> AdminUsersListResponseDto.from(
                        user,
                        safetyEventCountsByUserId.getOrDefault(user.getId(), 0L)
                ))
        );
    }
}
