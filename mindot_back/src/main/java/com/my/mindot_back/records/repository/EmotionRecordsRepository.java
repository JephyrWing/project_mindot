// emotion_records 테이블의 감정 기록을 저장, 조회하는 repository
package com.my.mindot_back.records.repository;

import com.my.mindot_back.records.entity.EmotionRecords;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmotionRecordsRepository
        extends JpaRepository<EmotionRecords, Long> {
}
