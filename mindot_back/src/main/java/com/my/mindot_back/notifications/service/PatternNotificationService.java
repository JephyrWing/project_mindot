// 최근 8주 반복 패턴을 알림으로 생성하고 조회하는 Service
package com.my.mindot_back.notifications.service;

import com.my.mindot_back.notifications.dto.PatternNotificationPageResponseDto;
import com.my.mindot_back.notifications.dto.PatternNotificationResponseDto;
import com.my.mindot_back.notifications.dto.UnreadNotificationCountResponseDto;
import com.my.mindot_back.notifications.entity.NotificationPreferences;
import com.my.mindot_back.notifications.entity.PatternNotification;
import com.my.mindot_back.notifications.repository.NotificationPreferencesRepository;
import com.my.mindot_back.notifications.repository.PatternNotificationRepository;
import com.my.mindot_back.reports.dto.RepeatedEmotionPatternDto;
import com.my.mindot_back.reports.service.RepeatedEmotionPatternService;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PatternNotificationService {

    private static final Map<String, String> EMOTION_LABELS = Map.ofEntries(
            Map.entry("ANXIETY", "불안"),
            Map.entry("FRUSTRATION", "좌절"),
            Map.entry("SADNESS", "슬픔"),
            Map.entry("ANGER", "분노"),
            Map.entry("LONELINESS", "외로움"),
            Map.entry("SHAME", "수치심"),
            Map.entry("GUILT", "죄책감"),
            Map.entry("FEAR", "두려움"),
            Map.entry("JOY", "기쁨"),
            Map.entry("RELIEF", "안도")
    );

    private static final Map<String, String> WEEKDAY_LABELS = Map.ofEntries(
            Map.entry("MONDAY", "월요일"),
            Map.entry("TUESDAY", "화요일"),
            Map.entry("WEDNESDAY", "수요일"),
            Map.entry("THURSDAY", "목요일"),
            Map.entry("FRIDAY", "금요일"),
            Map.entry("SATURDAY", "토요일"),
            Map.entry("SUNDAY", "일요일")
    );

    private static final Map<String, String> TIME_BUCKET_LABELS = Map.ofEntries(
            Map.entry("DAWN", "새벽"),
            Map.entry("MORNING", "아침"),
            Map.entry("AFTERNOON", "오후"),
            Map.entry("EVENING", "저녁"),
            Map.entry("NIGHT", "밤")
    );

    private static final Map<String, String> RECOMMENDED_ACTIONS =
            Map.ofEntries(
                    Map.entry(
                            "ANXIETY",
                            "걱정되는 생각과 지금 확인할 수 있는 사실을 나누어 기록해 보세요."
                    ),
                    Map.entry(
                            "FRUSTRATION",
                            "지금 바꿀 수 있는 행동 한 가지를 정해 작은 단계부터 시작해 보세요."
                    ),
                    Map.entry(
                            "SADNESS",
                            "현재 감정을 한 문장으로 기록하고 신뢰할 수 있는 사람과 나눠 보세요."
                    ),
                    Map.entry(
                            "ANGER",
                            "바로 반응하기 전에 잠시 멈추고 감정의 원인을 한 문장으로 적어 보세요."
                    ),
                    Map.entry(
                            "LONELINESS",
                            "부담 없이 연락할 수 있는 사람 한 명에게 짧은 안부를 보내 보세요."
                    ),
                    Map.entry(
                            "FEAR",
                            "가장 걱정되는 상황과 실제로 준비할 수 있는 행동을 구분해 보세요."
                    )
            );

    private final PatternNotificationRepository
            patternNotificationRepository;

    private final NotificationPreferencesRepository
            notificationPreferencesRepository;

    private final RepeatedEmotionPatternService
            repeatedEmotionPatternService;

    private final UsersRepository usersRepository;

    // 오늘 기준 최우선 반복 패턴 알림 생성
    @Transactional
    public PatternNotificationResponseDto generateForUser(
            Long userId
    ) {
        Users user = findUser(userId);

        NotificationPreferences preferences =
                notificationPreferencesRepository
                        .findByUser_Id(userId)
                        .filter(
                                NotificationPreferences
                                        ::isPatternAlertEnabled
                        )
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "반복 패턴 알림이 비활성화되어 있습니다."
                                )
                        );

        List<RepeatedEmotionPatternDto> patterns =
                repeatedEmotionPatternService
                        .analyzeRecentEightWeeksUpToToday(userId);

        if (patterns.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "알림을 생성할 반복 패턴이 없습니다."
            );
        }

        ZoneId zoneId = ZoneId.of(user.getTimezone());
        LocalDate windowEnd = LocalDate.now(zoneId);
        LocalDate windowStart = windowEnd.minusDays(55);

        String todayWeekday = windowEnd
                .getDayOfWeek()
                .name();

        /*
         * 특정 요일 패턴은 오늘 요일과 일치할 때만 알림 대상
         * 요일이 없는 패턴은 여러 요일에 걸친 패턴이므로 요일 제한 없이 대상
         */
        RepeatedEmotionPatternDto pattern = patterns.stream()
                .filter(candidate ->
                        candidate.weekday() == null
                                || candidate.weekday()
                                .equals(todayWeekday)
                )
                .findFirst()
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "오늘 알림을 생성할 반복 패턴이 없습니다."
                        )
                );

        String patternKey = createPatternKey(pattern);

        String deduplicationKey = createDeduplicationKey(
                userId,
                patternKey,
                pattern,
                windowEnd
        );

        // 같은 주에 이미 생성된 동일 패턴 알림 재사용
        return patternNotificationRepository
                .findByDeduplicationKey(deduplicationKey)
                .map(PatternNotificationResponseDto::from)
                .orElseGet(() -> {
                    PatternNotification notification =
                            PatternNotification.create(
                                    user,
                                    pattern,
                                    patternKey,
                                    windowStart,
                                    windowEnd,
                                    "최근 8주 반복 감정 패턴",
                                    createMessage(pattern),
                                    createRecommendedAction(pattern),
                                    deduplicationKey
                            );

                    return PatternNotificationResponseDto.from(
                            patternNotificationRepository.save(
                                    notification
                            )
                    );
                });
    }

    // 사용자 알림 최신순 페이징 조회
    @Transactional(readOnly = true)
    public PatternNotificationPageResponseDto getNotifications(
            Long userId,
            int page,
            int size
    ) {
        findUser(userId);

        Page<PatternNotification> result =
                patternNotificationRepository
                        .findAllByUser_IdAndDeletedAtIsNullOrderByCreatedAtDesc(
                                userId,
                                PageRequest.of(page, size)
                        );

        return PatternNotificationPageResponseDto.from(result);
    }

    // 읽지 않은 알림 수 조회
    @Transactional(readOnly = true)
    public UnreadNotificationCountResponseDto getUnreadCount(
            Long userId
    ) {
        findUser(userId);

        long unreadCount =
                patternNotificationRepository
                        .countByUser_IdAndReadAtIsNullAndDeletedAtIsNull(
                                userId
                        );

        return new UnreadNotificationCountResponseDto(
                unreadCount
        );
    }

    // 사용자 소유 알림 읽음 처리
    @Transactional
    public PatternNotificationResponseDto markAsRead(
            Long userId,
            Long notificationId
    ) {
        PatternNotification notification =
                patternNotificationRepository
                        .findByIdAndUser_IdAndDeletedAtIsNull(
                                notificationId,
                                userId
                        )
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "알림을 찾을 수 없습니다."
                                )
                        );

        notification.markRead();

        return PatternNotificationResponseDto.from(
                notification
        );
    }

    // 사용자 소유 알림 삭제 처리
    @Transactional
    public void deleteNotification(
            Long userId,
            Long notificationId
    ) {
        PatternNotification notification =
                patternNotificationRepository
                        .findByIdAndUser_IdAndDeletedAtIsNull(
                                notificationId,
                                userId
                        )
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "알림을 찾을 수 없습니다."
                                )
                        );

        notification.delete();
    }

    // 감정·요일·시간대 조합 식별값 생성
    private String createPatternKey(
            RepeatedEmotionPatternDto pattern
    ) {
        String weekday = pattern.weekday() == null
                ? "ANY"
                : pattern.weekday();

        return pattern.emotionCode()
                + "|"
                + weekday
                + "|"
                + pattern.timeBucket();
    }

    // 동일 패턴의 주간 중복 알림 방지값 생성
    private String createDeduplicationKey(
            Long userId,
            String patternKey,
            RepeatedEmotionPatternDto pattern,
            LocalDate windowEnd
    ) {
        WeekFields weekFields = WeekFields.ISO;

        int weekBasedYear =
                windowEnd.get(weekFields.weekBasedYear());

        int weekOfYear =
                windowEnd.get(weekFields.weekOfWeekBasedYear());

        return userId
                + ":"
                + patternKey
                + ":"
                + pattern.patternLevel().name()
                + ":"
                + weekBasedYear
                + "-W"
                + String.format("%02d", weekOfYear);
    }

    // 반복 패턴 알림 본문 생성
    private String createMessage(
            RepeatedEmotionPatternDto pattern
    ) {
        String emotion = EMOTION_LABELS.getOrDefault(
                pattern.emotionCode(),
                pattern.emotionCode()
        );

        String weekday = pattern.weekday() == null
                ? "여러 요일"
                : WEEKDAY_LABELS.getOrDefault(
                pattern.weekday(),
                pattern.weekday()
        );

        String timeBucket = TIME_BUCKET_LABELS.getOrDefault(
                pattern.timeBucket(),
                pattern.timeBucket()
        );

        return weekday
                + " "
                + timeBucket
                + " 시간대에 "
                + emotion
                + " 감정이 최근 8주 동안 "
                + pattern.occurrenceCount()
                + "회 기록됐습니다.";
    }

    // 감정 코드별 간단한 추천 행동 생성
    private String createRecommendedAction(
            RepeatedEmotionPatternDto pattern
    ) {
        return RECOMMENDED_ACTIONS.getOrDefault(
                pattern.emotionCode(),
                "현재 감정과 상황을 한 문장으로 기록하고 작은 행동 하나를 정해 보세요."
        );
    }

    // 사용자 존재 여부 확인
    private Users findUser(Long userId) {
        return usersRepository.findById(userId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "사용자를 찾을 수 없습니다."
                        )
                );
    }
}
