// 감정 기록·확정 CBT 변경 시 영향을 받는 주간·월간 리포트 캐시를 삭제하는 Service
package com.my.mindot_back.reports.service;

import com.my.mindot_back.reports.repository.ReportsRepository;
import com.my.mindot_back.users.repository.UsersRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.my.mindot_back.reports.entity.ReportType;

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
                .forEach(date -> {
                    /*
                     * 월간 리포트는 변경된 날짜가 포함된 해당 월만 삭제
                     */
                    reportsRepository
                            .deleteByUser_IdAndReportTypeAndPeriodStartLessThanEqualAndPeriodEndGreaterThanEqual(
                                    userId,
                                    ReportType.MONTHLY,
                                    date,
                                    date
                            );

                    /*
                     * 주간 리포트의 반복 패턴은 선택 주를 포함한 최근 8주를 사용
                     *
                     * 변경된 날짜가 직접 포함된 주간 리포트뿐 아니라
                     * 해당 날짜를 최근 8주 범위로 사용하는 이후 주간 리포트도 삭제
                     */
                    reportsRepository
                            .deleteByUser_IdAndReportTypeAndPeriodStartLessThanEqualAndPeriodEndGreaterThanEqual(
                                    userId,
                                    ReportType.WEEKLY,
                                    date.plusWeeks(7),
                                    date
                            );
                });
    }
}
