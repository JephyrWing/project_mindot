// 사용자별 반복 패턴 알림 설정 조회와 변경 Service
package com.my.mindot_back.notifications.service;

import com.my.mindot_back.notifications.dto.NotificationPreferencesResponseDto;
import com.my.mindot_back.notifications.dto.NotificationPreferencesUpdateRequestDto;
import com.my.mindot_back.notifications.entity.NotificationPreferences;
import com.my.mindot_back.notifications.repository.NotificationPreferencesRepository;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class NotificationPreferencesService {

    private final NotificationPreferencesRepository
            notificationPreferencesRepository;

    private final UsersRepository usersRepository;

    // 저장된 알림 설정 조회
    @Transactional(readOnly = true)
    public NotificationPreferencesResponseDto getPreferences(
            Long userId
    ) {
        Users user = findUser(userId);

        return notificationPreferencesRepository
                .findByUser_Id(userId)
                .map(NotificationPreferencesResponseDto::from)
                .orElseGet(() ->
                        NotificationPreferencesResponseDto
                                .defaultValue(user.getTimezone())
                );
    }

    // 알림 설정 생성 또는 변경
    @Transactional
    public NotificationPreferencesResponseDto updatePreferences(
            Long userId,
            NotificationPreferencesUpdateRequestDto dto
    ) {
        Users user = findUser(userId);

        NotificationPreferences preferences =
                notificationPreferencesRepository
                        .findByUser_Id(userId)
                        .orElseGet(() ->
                                NotificationPreferences.createDefault(user)
                        );

        preferences.updatePatternAlert(
                dto.patternAlertEnabled(),
                dto.preferredTime()
        );

        NotificationPreferences savedPreferences =
                notificationPreferencesRepository.saveAndFlush(preferences);

        return NotificationPreferencesResponseDto.from(
                savedPreferences
        );
    }

    // 사용자 존재 여부 확인
    private Users findUser(Long userId) {
        return usersRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "사용자를 찾을 수 없습니다."
                ));
    }
}