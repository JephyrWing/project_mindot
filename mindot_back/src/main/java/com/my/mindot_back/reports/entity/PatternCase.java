// 반복 패턴과 이를 뒷받침하는 완료 감정 기록의 연결 Entity
package com.my.mindot_back.reports.entity;

import com.my.mindot_back.records.entity.EmotionRecords;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "pattern_cases", uniqueConstraints =
        @UniqueConstraint(name = "uk_pattern_cases_pattern_record", columnNames = {"personal_pattern_id", "emotion_record_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatternCase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "personal_pattern_id", nullable = false)
    private PersonalPattern personalPattern;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "emotion_record_id", nullable = false)
    private EmotionRecords emotionRecord;

    public static PatternCase create(PersonalPattern pattern, EmotionRecords record) {
        PatternCase patternCase = new PatternCase();
        patternCase.personalPattern = pattern;
        patternCase.emotionRecord = record;
        return patternCase;
    }
}
