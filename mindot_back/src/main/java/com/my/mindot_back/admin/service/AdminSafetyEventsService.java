// 관리자가 안전 신호 이벤트 목록과 상세를 조회하는 기능을 처리하는 Service
package com.my.mindot_back.admin.service;

import com.my.mindot_back.admin.dto.AdminSafetyEventDetailResponseDto;
import com.my.mindot_back.admin.dto.AdminSafetyEventListResponseDto;
import com.my.mindot_back.safety.repository.SafetyEventsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminSafetyEventsService {

    // 안전 신호 이벤트 조회 Repository
    private final SafetyEventsRepository safetyEventsRepository;

    // 생성 시각 최신순으로 안전 신호 이벤트 목록 조회
    @Transactional(readOnly = true)
    public List<AdminSafetyEventListResponseDto> getSafetyEvents() {
        return safetyEventsRepository.findAll(
                        Sort.by(
                                Sort.Direction.DESC,
                                "createdAt"
                        )
                )
                .stream()
                .map(AdminSafetyEventListResponseDto::from)
                .toList();
    }

    // 관리자만 안전 신호에 연결된 감정 기록 원문 상세 조회
    @Transactional(readOnly = true)
    public AdminSafetyEventDetailResponseDto getSafetyEvent(
            Long safetyEventId
    ) {
        return safetyEventsRepository.findById(safetyEventId)
                .map(AdminSafetyEventDetailResponseDto::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "안전 신호 이벤트를 찾을 수 없습니다."
                ));
    }
}