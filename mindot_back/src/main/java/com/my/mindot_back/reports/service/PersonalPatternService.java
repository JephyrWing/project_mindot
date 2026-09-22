// 최근 56일 반복 패턴을 영속 식별자와 근거 기록에 동기화하고 피드백을 관리하는 Service
package com.my.mindot_back.reports.service;

import com.my.mindot_back.records.entity.CompletionStatus;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.TimeBucket;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.reports.dto.*;
import com.my.mindot_back.reports.entity.PatternCase;
import com.my.mindot_back.reports.entity.PersonalPattern;
import com.my.mindot_back.reports.repository.PatternCaseRepository;
import com.my.mindot_back.reports.repository.PersonalPatternRepository;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PersonalPatternService {
    private final UsersRepository usersRepository;
    private final EmotionRecordsRepository emotionRecordsRepository;
    private final RepeatedEmotionPatternService repeatedEmotionPatternService;
    private final PersonalPatternRepository personalPatternRepository;
    private final PatternCaseRepository patternCaseRepository;

    // 사용자 행을 잠근 상태에서 목록과 근거 연결을 갱신해 동시 요청 중복 생성을 방지
    @Transactional
    public List<PersonalPatternResponseDto> list(Long userId) {
        return refresh(userId).stream()
                .map(PersonalPatternResponseDto::from)
                .toList();
    }

    // 목록과 동일한 기준으로 다시 집계한 뒤 본인 소유 패턴만 상세 조회
    @Transactional
    public PersonalPatternDetailResponseDto detail(Long userId, Long patternId) {
        refresh(userId);
        PersonalPattern pattern = ownedPattern(userId, patternId);
        List<PatternEvidenceRecordDto> evidence = patternCaseRepository
                .findAllWithRecordByPatternId(patternId).stream()
                .map(PatternCase::getEmotionRecord)
                .filter(record -> record.getUser().getId().equals(userId)
                        && record.getCompletionStatus() == CompletionStatus.COMPLETE)
                .sorted(Comparator.comparing(EmotionRecords::getOccurredAt).reversed()
                        .thenComparing(EmotionRecords::getId, Comparator.reverseOrder()))
                .map(PatternEvidenceRecordDto::from)
                .toList();
        return PersonalPatternDetailResponseDto.from(pattern, evidence);
    }

    // 동일한 패턴에 대한 사용자의 선택을 변경 가능하도록 저장
    @Transactional
    public PatternFeedbackResponseDto feedback(
            Long userId, Long patternId, PatternFeedbackRequestDto request) {
        PersonalPattern pattern = ownedPattern(userId, patternId);
        pattern.setFeedback(request.feedback());
        return PatternFeedbackResponseDto.from(pattern);
    }

    private PersonalPattern ownedPattern(Long userId, Long patternId) {
        return personalPatternRepository.findByIdAndUser_Id(patternId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "반복 패턴을 찾을 수 없습니다."));
    }

    private List<PersonalPattern> refresh(Long userId) {
        Users user = usersRepository.findLockedById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        ZoneId zoneId = ZoneId.of(user.getTimezone());
        LocalDate windowEnd = LocalDate.now(zoneId);
        LocalDate windowStart = windowEnd.minusDays(55);
        Instant start = windowStart.atStartOfDay(zoneId).toInstant();
        Instant endExclusive = windowEnd.plusDays(1).atStartOfDay(zoneId).toInstant();

        List<EmotionRecords> records = emotionRecordsRepository
                .findAllByUser_IdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAsc(
                        userId, start, endExclusive).stream()
                .filter(record -> record.getCompletionStatus() == CompletionStatus.COMPLETE)
                .toList();
        List<RepeatedEmotionPatternDto> candidates = repeatedEmotionPatternService
                .analyzeRecentEightWeeks(userId, windowEnd);
        Map<String, PersonalPattern> existing = personalPatternRepository
                .findAllByUser_Id(userId).stream()
                .collect(Collectors.toMap(PersonalPattern::getPatternKey, Function.identity()));
        List<PersonalPattern> active = new ArrayList<>();

        for (RepeatedEmotionPatternDto candidate : candidates) {
            String key = key(candidate.emotionCode(), candidate.weekday(), candidate.timeBucket());
            PersonalPattern pattern = existing.remove(key);
            if (pattern == null) pattern = PersonalPattern.create(user, key);
            pattern.update(candidate.emotionCode(), candidate.weekday(), candidate.timeBucket(),
                    candidate.patternLevel(), candidate.occurrenceCount(),
                    candidate.distinctDateCount(), candidate.observedWeekCount(),
                    windowStart, windowEnd);
            pattern = personalPatternRepository.saveAndFlush(pattern);

            List<PatternCase> cases = new ArrayList<>();
            for (EmotionRecords record : records) {
                if (matches(record, candidate, zoneId)) {
                    cases.add(PatternCase.create(pattern, record));
                }
            }
            patternCaseRepository.deleteAllByPatternId(pattern.getId());
            patternCaseRepository.saveAll(cases);
            active.add(pattern);
        }

        // 더는 반복 기준을 만족하지 않는 패턴은 목록에서 빼고 상세 근거는 비움
        for (PersonalPattern inactive : existing.values()) {
            inactive.update(inactive.getEmotionCode(), inactive.getWeekday(),
                    inactive.getTimeBucket(), inactive.getPatternLevel(),
                    0, 0, 0, windowStart, windowEnd);
            patternCaseRepository.deleteAllByPatternId(inactive.getId());
        }
        return active;
    }

    private boolean matches(EmotionRecords record, RepeatedEmotionPatternDto candidate,
                            ZoneId zoneId) {
        if (!candidate.emotionCode().equals(record.getPrimaryEmotionCode())) return false;
        var local = record.getOccurredAt().atZone(zoneId);
        if (!candidate.timeBucket().equals(TimeBucket.fromHour(local.getHour()).name())) {
            return false;
        }
        return candidate.weekday() == null
                || candidate.weekday().equals(local.getDayOfWeek().name());
    }

    private String key(String emotionCode, String weekday, String timeBucket) {
        return emotionCode + ":" + (weekday == null ? "ALL" : weekday) + ":" + timeBucket;
    }
}
