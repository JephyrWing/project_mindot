// 기존 화면과 같은 우선순위로 마음 돌봄 추천을 만들고 사용자 피드백을 저장하는 Service
package com.my.mindot_back.dailycare.service;

import com.my.mindot_back.dailycare.dto.DailyCareFeedbackRequestDto;
import com.my.mindot_back.dailycare.dto.DailyCareRecommendationResponseDto;
import com.my.mindot_back.dailycare.entity.DailyCareRecommendation;
import com.my.mindot_back.dailycare.repository.DailyCareRecommendationRepository;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.ReflectionSessionStatus;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.reports.dto.RepeatedEmotionPatternDto;
import com.my.mindot_back.reports.service.RepeatedEmotionPatternService;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DailyCareRecommendationService {
    private static final Set<String> ANXIOUS = Set.of("ANXIETY", "FEAR", "ANGER", "FRUSTRATION");
    private static final Set<String> LOW_ENERGY = Set.of("SADNESS", "DISAPPOINTMENT", "LONELINESS", "GUILT");
    private static final Set<String> POSITIVE = Set.of("JOY", "RELIEF", "ACHIEVEMENT", "CALM", "GRATITUDE", "EXCITEMENT");
    private static final Map<String, String> EMOTION_LABELS = Map.ofEntries(
            Map.entry("ANXIETY", "불안"),
            Map.entry("FEAR", "두려움"),
            Map.entry("ANGER", "분노"),
            Map.entry("FRUSTRATION", "답답함"),
            Map.entry("SADNESS", "슬픔"),
            Map.entry("DISAPPOINTMENT", "실망"),
            Map.entry("LONELINESS", "외로움"),
            Map.entry("GUILT", "죄책감"),
            Map.entry("JOY", "기쁨"),
            Map.entry("RELIEF", "안도"),
            Map.entry("ACHIEVEMENT", "성취감"),
            Map.entry("CALM", "평온"),
            Map.entry("GRATITUDE", "감사"),
            Map.entry("EXCITEMENT", "설렘"),
            Map.entry("SHAME", "수치심"),
            Map.entry("OTHER", "기타")
    );
    private static final Map<String, String> WEEKDAY_LABELS = Map.of(
            "MONDAY", "월요일", "TUESDAY", "화요일", "WEDNESDAY", "수요일",
            "THURSDAY", "목요일", "FRIDAY", "금요일", "SATURDAY", "토요일", "SUNDAY", "일요일"
    );
    private static final Map<String, String> TIME_LABELS = Map.of(
            "DAWN", "새벽", "MORNING", "아침", "AFTERNOON", "오후", "EVENING", "저녁", "NIGHT", "밤"
    );

    private final UsersRepository usersRepository;
    private final EmotionRecordsRepository emotionRecordsRepository;
    private final ReflectionSessionsRepository reflectionSessionsRepository;
    private final RepeatedEmotionPatternService repeatedEmotionPatternService;
    private final DailyCareRecommendationRepository recommendationRepository;

    private record Proposal(String basisKey, String title, String description, String activity,
                            String source, Long emotionRecordId, Long reflectionSessionId) {}

    private record RecentTrend(String emotionCode, long count, Short intensity, Long recordId) {}

    // 같은 사용자의 동시 요청을 직렬화해 하루에 추천을 하나만 생성
    @Transactional
    public DailyCareRecommendationResponseDto getToday(Long userId) {
        Users user = usersRepository.findLockedById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."
                ));
        ZoneId zoneId = ZoneId.of(user.getTimezone());
        LocalDate today = LocalDate.now(zoneId);
        EmotionRecords latest = emotionRecordsRepository.findFirstByUser_IdOrderByOccurredAtDescIdDesc(userId)
                .orElse(null);
        List<EmotionRecords> recentRecords = emotionRecordsRepository
                .findAllByUser_IdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAsc(
                        userId,
                        today.minusDays(6).atStartOfDay(zoneId).toInstant(),
                        today.plusDays(1).atStartOfDay(zoneId).toInstant());
        RecentTrend trend = findRecentTrend(recentRecords, zoneId);
        List<ReflectionSessions> openSessions = reflectionSessionsRepository
                .findAllByUser_IdAndStatusOrderByCreatedAtDesc(
                        userId,
                        ReflectionSessionStatus.OPEN);
        ReflectionSessions open = openSessions.isEmpty() ? null : openSessions.get(0);
        List<RepeatedEmotionPatternDto> patterns = repeatedEmotionPatternService.analyzeRecentEightWeeksUpToToday(userId);
        Proposal proposal = propose(
                latest, open, patterns.isEmpty() ? null : patterns.get(0), trend);

        DailyCareRecommendation recommendation = recommendationRepository
                .findByUser_IdAndRecommendationDate(userId, today)
                .orElse(null);

        if (recommendation == null) {
            recommendation = DailyCareRecommendation.create(
                    user, today, proposal.basisKey(), proposal.title(),
                    proposal.description(), proposal.activity(),
                    proposal.source(), proposal.emotionRecordId(),
                    proposal.reflectionSessionId());
            recommendation = recommendationRepository.save(recommendation);
        } else if (!recommendation.getBasisKey()
                .equals(proposal.basisKey())) {
            recommendation.replace(
                    proposal.basisKey(), proposal.title(), proposal.description(), proposal.activity(),
                    proposal.source(), proposal.emotionRecordId(), proposal.reflectionSessionId());
        }
        return DailyCareRecommendationResponseDto.from(recommendation);
    }

    // 추천 ID와 현재 로그인 사용자가 일치할 때만 피드백 저장
    @Transactional
    public DailyCareRecommendationResponseDto saveFeedback(
            Long userId,
            Long recommendationId,
            DailyCareFeedbackRequestDto.Feedback feedback) {
        DailyCareRecommendation recommendation = recommendationRepository.findByIdAndUser_Id(recommendationId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "추천을 찾을 수 없습니다."
                ));
        recommendation.setFeedback(feedback.name());
        return DailyCareRecommendationResponseDto.from(recommendation);
    }

    // 최근 7일에 2일 이상, 3건 이상 기록되고 한 감정이 과반일 때 흐름으로 판단
    private RecentTrend findRecentTrend(List<EmotionRecords> records, ZoneId zoneId) {
        List<EmotionRecords> valid = records.stream()
                .filter(record -> record.getPrimaryEmotionCode() != null
                        && !record.getPrimaryEmotionCode().isBlank())
                .toList();
        if (valid.size() < 3 || valid.stream()
                .map(record -> record.getOccurredAt().atZone(zoneId).toLocalDate())
                .distinct().count() < 2) {
            return null;
        }
        Map<String, Long> counts = valid.stream().map(EmotionRecords::getPrimaryEmotionCode)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        Map<String, Instant> latestByCode = valid.stream().collect(Collectors.toMap(
                EmotionRecords::getPrimaryEmotionCode,
                EmotionRecords::getOccurredAt,
                (first, second) -> first.isAfter(second) ? first : second));
        Map.Entry<String, Long> dominant = counts.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                        .thenComparing(entry -> latestByCode.get(entry.getKey())))
                .orElseThrow();
        if (dominant.getValue() * 2 <= valid.size()) {
            return null;
        }
        EmotionRecords representative = valid.stream()
                .filter(record -> dominant.getKey().equals(record.getPrimaryEmotionCode()))
                .max(Comparator.comparing(EmotionRecords::getOccurredAt))
                .orElseThrow();
        return new RecentTrend(dominant.getKey(), dominant.getValue(),
                representative.getPrimaryIntensity(), representative.getId());
    }

    // 최근 감정 흐름을 먼저 사용하고, 오래된 패턴은 현재 감정과 일치할 때만 사용
    private Proposal propose(EmotionRecords latest, ReflectionSessions open,
                             RepeatedEmotionPatternDto pattern, RecentTrend trend) {
        Long recordId = latest == null ? null : latest.getId();
        Long sessionId = open == null ? null : open.getId();
        if (trend != null) {
            String code = trend.emotionCode();
            String emotion = EMOTION_LABELS.getOrDefault(code, code);
            String description = "최근 7일 동안 " + emotion + " 감정이 " + trend.count()
                    + "회 기록된 현재의 흐름을 기준으로 안내합니다.";
            boolean patternMatches = pattern != null && code.equals(pattern.emotionCode());
            if (patternMatches) {
                description += " 최근 8주 반복 패턴과도 이어지는 감정입니다.";
            }
            return moodProposal(code, trend.intensity(),
                    "trend:" + code + ":" + trend.count() + ":" + trend.recordId() + ":" + patternMatches,
                    description, "recent-trend", trend.recordId());
        }
        if (latest == null) {
            return new Proposal("empty", "오늘의 마음을 짧게 기록해 보세요.",
                    "감정 기록이 쌓이면 현재 마음에 맞는 돌봄 활동을 안내해 드립니다.",
                    "RECORD", "empty", null, null);
        }
        String code = latest.getPrimaryEmotionCode();
        String basis = "record:" + recordId + ":" + code + ":" + latest.getPrimaryIntensity();
        if (open != null && open.getEmotionRecord().getId().equals(recordId)) {
            return new Proposal("open:" + sessionId, "멈춰 둔 CBT 성찰을 이어가 보세요.",
                    "최근 감정에서 시작한 대화를 이어서 생각을 차분하게 정리할 수 있습니다.",
                    "CBT", "open-reflection", recordId, sessionId);
        }
        if (pattern != null && code != null && code.equals(pattern.emotionCode())) {
            String weekday = pattern.weekday() == null ? "여러 요일"
                    : WEEKDAY_LABELS.getOrDefault(pattern.weekday(), pattern.weekday());
            String time = TIME_LABELS.getOrDefault(pattern.timeBucket(), pattern.timeBucket());
            String emotion = EMOTION_LABELS.getOrDefault(code, code);
            String description = "최근 8주 동안 " + weekday + " " + time + "에 " + emotion + " 감정이 "
                    + pattern.occurrenceCount() + "회 기록된 패턴을 기준으로 안내합니다.";
            return moodProposal(code, latest.getPrimaryIntensity(),
                    "pattern:" + code + ":" + pattern.weekday() + ":" + pattern.timeBucket()
                            + ":" + pattern.occurrenceCount() + ":" + recordId,
                    description, "pattern", recordId);
        }
        return moodProposal(code, latest.getPrimaryIntensity(), basis,
                "가장 최근에 기록한 감정을 기준으로 안내합니다.", "latest-record", recordId);
    }

    // 감정 종류를 먼저 해석해 긍정 감정의 높은 강도를 긴장으로 오인하지 않음
    private Proposal moodProposal(String code, Short intensity, String basis, String description,
                                  String source, Long recordId) {
        if (code != null && POSITIVE.contains(code)) {
            return new Proposal(basis, "최근의 좋은 마음을 짧게 기록해 두세요.", description,
                    "RECORD", source, recordId, null);
        }
        if (code != null && ANXIOUS.contains(code)) {
            return new Proposal(basis, "3분 호흡으로 긴장을 천천히 낮춰 보세요.", description,
                    "BREATHING", source, recordId, null);
        }
        if (code != null && LOW_ENERGY.contains(code)) {
            return new Proposal(basis, "짧은 명상으로 지금의 마음을 살펴보세요.", description,
                    "MEDITATION", source, recordId, null);
        }
        if (intensity != null && intensity >= 7) {
            return new Proposal(basis, "3분 호흡으로 강한 감정에서 잠시 거리를 두세요.", description,
                    "BREATHING", source, recordId, null);
        }
        return new Proposal(basis, "최근 감정을 CBT 성찰로 조금 더 살펴보세요.", description,
                "CBT", source, recordId, null);
    }
}
