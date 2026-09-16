// 감정 기록, 확정 CBT 변경 시 해당 날짜와 겹치는 리포트 캐시만 삭제하는 Service
package com.my.mindot_back.reports.service;

import com.my.mindot_back.reports.repository.ReportsRepository;
import com.my.mindot_back.users.repository.UsersRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;

@Service
@RequiredArgsConstructor
public class ReportCacheInvalidationService {

    private final ReportsRepository reportsRepository;
    private final UsersRepository usersRepository;

    // 변경된 기록 시각이 포함된 주간, 월간 리포트만 찾아 캐시 삭제
    @Transactional
    public void invalidateByOccurredAt(
            Long userId,
            Instant... occurredAts
    ) {
        String timezone = usersRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "사용자를 찾을 수 없습니다."
                ))
                .getTimezone();

        ZoneId zoneId = ZoneId.of(timezone);

        Arrays.stream(occurredAts)
                .filter(java.util.Objects::nonNull)
                .map(occurredAt -> occurredAt
                        .atZone(zoneId)
                        .toLocalDate())
                .distinct()
                .forEach(date -> reportsRepository
                        .deleteByUser_IdAndPeriodStartLessThanEqualAndPeriodEndGreaterThanEqual(
                                userId,
                                date,
                                date
                        ));
    }
}
