-- 주간 리포트 생성 시험용 더미데이터
-- 실행 전 Spring Boot 애플리케이션을 한 번 실행해 Hibernate가 테이블을 만들고
-- DistortionTypesInitializer가 인지왜곡 기준 데이터를 저장해야 한다.

\set ON_ERROR_STOP on

BEGIN;

SET LOCAL client_encoding = 'UTF8';

-- 테스트 계정 비밀번호를 BCrypt로 만들 때 사용한다.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TEMP TABLE weekly_report_seed_context (
    user_email text PRIMARY KEY,
    report_week_start date NOT NULL,
    user_timezone text NOT NULL,
    seed_name text NOT NULL
) ON COMMIT DROP;

-- 미래 시각이 섞이지 않도록 가장 최근에 끝난 주를 리포트 대상 주로 사용한다.
INSERT INTO weekly_report_seed_context (
    user_email,
    report_week_start,
    user_timezone,
    seed_name
)
VALUES (
    'weekly-report-test@mindot.local',
    date_trunc('week', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date - 7,
    'Asia/Seoul',
    'weekly-report-v1'
);

-- 로그인 가능한 전용 테스트 계정을 생성한다.
-- email: weekly-report-test@mindot.local / password: Password1!
INSERT INTO users (
    email,
    password_hash,
    display_name,
    timezone,
    locale,
    status,
    user_role,
    created_at,
    updated_at
)
SELECT
    context.user_email,
    crypt('Password1!', gen_salt('bf', 10)),
    '주간리포트 테스트',
    context.user_timezone,
    'ko-KR',
    'ACTIVE',
    'ROLE_USER',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM weekly_report_seed_context context
ON CONFLICT (email) DO UPDATE
SET
    password_hash = EXCLUDED.password_hash,
    display_name = EXCLUDED.display_name,
    timezone = EXCLUDED.timezone,
    locale = EXCLUDED.locale,
    status = EXCLUDED.status,
    user_role = EXCLUDED.user_role,
    deleted_at = NULL,
    updated_at = CURRENT_TIMESTAMP;

-- 인지왜곡 변화 통계에 필요한 기준 코드가 준비됐는지 먼저 확인한다.
DO $$
DECLARE
    missing_codes text;
BEGIN
    SELECT string_agg(required.code, ', ' ORDER BY required.code)
    INTO missing_codes
    FROM unnest(ARRAY[
        'CATASTROPHIZING_FORTUNE_TELLING',
        'MIND_READING',
        'DISQUALIFYING_DISCOUNTING_POSITIVE',
        'SHOULD_MUST_STATEMENTS',
        'PERSONALIZATION'
    ]) AS required(code)
    WHERE NOT EXISTS (
        SELECT 1
        FROM distortion_types distortion_type
        WHERE distortion_type.code = required.code
    );

    IF missing_codes IS NOT NULL THEN
        RAISE EXCEPTION
            '인지왜곡 기준 데이터가 없습니다: %. 백엔드를 한 번 실행한 뒤 다시 시도하세요.',
            missing_codes;
    END IF;
END;
$$;

-- 같은 시드를 다시 실행하면 이 시드가 만든 데이터와 해당 주 리포트만 교체한다.
DELETE FROM session_distortions session_distortion
USING reflection_sessions reflection_session,
      emotion_records emotion_record,
      weekly_report_seed_context context,
      users seed_user
WHERE session_distortion.session_id = reflection_session.id
  AND reflection_session.emotion_record_id = emotion_record.id
  AND emotion_record.user_id = seed_user.id
  AND seed_user.email = context.user_email
  AND emotion_record.ai_meta ->> 'seed' = context.seed_name;

DELETE FROM reflection_sessions reflection_session
USING emotion_records emotion_record,
      weekly_report_seed_context context,
      users seed_user
WHERE reflection_session.emotion_record_id = emotion_record.id
  AND emotion_record.user_id = seed_user.id
  AND seed_user.email = context.user_email
  AND emotion_record.ai_meta ->> 'seed' = context.seed_name;

DELETE FROM reports report
USING weekly_report_seed_context context,
      users seed_user
WHERE report.user_id = seed_user.id
  AND seed_user.email = context.user_email
  AND report.report_type = 'WEEKLY'
  AND report.period_start BETWEEN context.report_week_start - 49
                              AND context.report_week_start;

DELETE FROM emotion_records emotion_record
USING weekly_report_seed_context context,
      users seed_user
WHERE emotion_record.user_id = seed_user.id
  AND seed_user.email = context.user_email
  AND emotion_record.ai_meta ->> 'seed' = context.seed_name;

-- 최근 8주 패턴과 선택 주의 분포를 함께 확인할 수 있는 감정 기록을 만든다.
-- week_offset 0이 생성 대상 주이고 -7까지가 반복 패턴 분석 이력이다.
WITH emotion_seed (
    seed_key,
    week_offset,
    day_offset,
    local_time,
    time_bucket,
    weekday_type,
    raw_text,
    situation_text,
    automatic_thought,
    primary_emotion_code,
    primary_intensity,
    secondary_emotions,
    context_category,
    related_person_type,
    details
) AS (
    VALUES
        -- 8주 연속 월요일 아침 불안: LONG_TERM 패턴
        ('anxiety-w7', -7, 0, TIME '08:30', 'MORNING', 'WEEKDAY', '주간 회의 발표가 걱정됐다.', '월요일 아침 주간 회의를 준비했다.', '발표 중 실수하면 모두 나를 부족하게 볼 것 같다.', 'ANXIETY', 8, '[{"code":"TENSION","intensity":6}]'::jsonb, 'WORK', 'COLLEAGUE', '{"bodyReaction":"가슴이 두근거림","behavior":"자료를 반복해서 확인함"}'::jsonb),
        ('anxiety-w6', -6, 0, TIME '08:30', 'MORNING', 'WEEKDAY', '회의 생각에 긴장됐다.', '월요일 팀 회의에서 진행 상황을 공유할 예정이었다.', '질문에 답하지 못하면 신뢰를 잃을 것 같다.', 'ANXIETY', 7, '[{"code":"TENSION","intensity":5}]'::jsonb, 'WORK', 'COLLEAGUE', '{"bodyReaction":"어깨가 굳음","behavior":"일찍 출근함"}'::jsonb),
        ('anxiety-w5', -5, 0, TIME '08:30', 'MORNING', 'WEEKDAY', '월요일 아침부터 마음이 조급했다.', '예정된 업무 점검 회의를 준비했다.', '준비가 충분하지 않은 것 같다.', 'ANXIETY', 7, '[]'::jsonb, 'WORK', 'SUPERVISOR', '{"bodyReaction":"속이 답답함","behavior":"메모를 계속 고침"}'::jsonb),
        ('anxiety-w4', -4, 0, TIME '08:30', 'MORNING', 'WEEKDAY', '회의 전에 불안감이 올라왔다.', '팀원들 앞에서 결과를 설명해야 했다.', '작은 오류도 크게 지적받을 것 같다.', 'ANXIETY', 8, '[{"code":"SHAME","intensity":4}]'::jsonb, 'WORK', 'COLLEAGUE', '{"bodyReaction":"손에 땀이 남","behavior":"발표를 미루고 싶었음"}'::jsonb),
        ('anxiety-w3', -3, 0, TIME '08:30', 'MORNING', 'WEEKDAY', '발표 준비 중 긴장했다.', '월요일 오전에 프로젝트 발표가 있었다.', '말을 더듬으면 발표 전체가 실패할 것이다.', 'ANXIETY', 7, '[]'::jsonb, 'WORK', 'COLLEAGUE', '{"bodyReaction":"목이 마름","behavior":"대본을 외움"}'::jsonb),
        ('anxiety-w2', -2, 0, TIME '08:30', 'MORNING', 'WEEKDAY', '한 주 시작부터 부담스러웠다.', '업무 우선순위를 공유하는 회의에 참석했다.', '내 계획이 부족하다고 평가받을 것 같다.', 'ANXIETY', 6, '[{"code":"TENSION","intensity":5}]'::jsonb, 'WORK', 'SUPERVISOR', '{"bodyReaction":"호흡이 짧아짐","behavior":"말할 내용을 적어 둠"}'::jsonb),
        ('anxiety-w1', -1, 0, TIME '08:30', 'MORNING', 'WEEKDAY', '회의 시작 전 많이 긴장됐다.', '지난주 성과를 보고하는 자리였다.', '성과가 기대보다 부족해 보일 것 같다.', 'ANXIETY', 7, '[]'::jsonb, 'WORK', 'SUPERVISOR', '{"bodyReaction":"배가 아픔","behavior":"수치를 여러 번 확인함"}'::jsonb),
        ('current-anxiety-monday', 0, 0, TIME '08:30', 'MORNING', 'WEEKDAY', '아침 회의를 앞두고 불안했다.', '팀 주간 회의에서 담당 업무를 발표했다.', '한 번 막히면 능력이 없다고 생각할 것이다.', 'ANXIETY', 8, '[{"code":"TENSION","intensity":6}]'::jsonb, 'WORK', 'COLLEAGUE', '{"interpretation":"평가 상황을 위협적으로 받아들임","bodyReaction":"심장이 빠르게 뜀","behavior":"발표 자료를 반복 확인함"}'::jsonb),

        -- 4주 범위 중 3주 화요일 오후 기쁨: SUSTAINED 패턴
        ('joy-w3', -3, 1, TIME '14:00', 'AFTERNOON', 'WEEKDAY', '업무 피드백이 좋아 기뻤다.', '작성한 기획안에 긍정적인 피드백을 받았다.', '내 노력이 제대로 전달된 것 같다.', 'JOY', 7, '[{"code":"ACHIEVEMENT","intensity":7}]'::jsonb, 'WORK', 'COLLEAGUE', '{"behavior":"동료와 결과를 공유함"}'::jsonb),
        ('joy-w2', -2, 1, TIME '14:00', 'AFTERNOON', 'WEEKDAY', '작업이 잘 마무리되어 뿌듯했다.', '예정보다 일찍 업무를 완료했다.', '차근차근 하면 해낼 수 있다.', 'JOY', 8, '[{"code":"RELIEF","intensity":6}]'::jsonb, 'WORK', 'SELF', '{"behavior":"다음 계획을 정리함"}'::jsonb),
        ('current-joy-tuesday', 0, 1, TIME '14:00', 'AFTERNOON', 'WEEKDAY', '칭찬을 받아 기분이 좋았다.', '고객이 결과물에 만족한다는 답변을 보냈다.', '준비한 만큼 좋은 결과가 나왔다.', 'JOY', 8, '[{"code":"ACHIEVEMENT","intensity":8}]'::jsonb, 'WORK', 'CUSTOMER', '{"bodyReaction":"몸이 가벼워짐","behavior":"팀원에게 감사 인사를 전함"}'::jsonb),

        -- 2주 연속 오전 평온: REPEATED 패턴
        ('calm-w1', -1, 3, TIME '09:00', 'MORNING', 'WEEKDAY', '산책 후 마음이 차분해졌다.', '출근 전에 공원을 천천히 걸었다.', '잠깐 멈추어도 괜찮다.', 'CALM', 6, '[]'::jsonb, 'DAILY_LIFE', 'SELF', '{"behavior":"호흡에 집중함"}'::jsonb),
        ('current-calm-tuesday', 0, 1, TIME '09:00', 'MORNING', 'WEEKDAY', '아침 루틴을 지켜 마음이 편안했다.', '일어나서 스트레칭과 짧은 명상을 했다.', '오늘 할 일을 하나씩 하면 된다.', 'CALM', 7, '[{"code":"RELIEF","intensity":4}]'::jsonb, 'HEALTH', 'SELF', '{"bodyReaction":"호흡이 편안함","behavior":"할 일 목록을 작성함"}'::jsonb),
        ('current-calm-thursday', 0, 3, TIME '10:00', 'MORNING', 'WEEKDAY', '집중해서 일하니 차분했다.', '알림을 끄고 한 시간 동안 한 가지 업무에 집중했다.', '집중할 환경을 만들면 해낼 수 있다.', 'CALM', 6, '[]'::jsonb, 'WORK', 'SELF', '{"behavior":"휴대폰을 서랍에 넣음"}'::jsonb),

        -- 선택 주의 나머지 분포와 근거 데이터
        ('current-sadness-monday', 0, 0, TIME '22:10', 'NIGHT', 'WEEKDAY', '친구의 답장이 없어 서운하고 슬펐다.', '친구에게 보낸 메시지에 하루 동안 답이 없었다.', '내가 중요하지 않아서 답하지 않는 것 같다.', 'SADNESS', 7, '[{"code":"LONELINESS","intensity":6}]'::jsonb, 'RELATIONSHIP', 'FRIEND', '{"bodyReaction":"가슴이 무거움","behavior":"메신저를 반복해서 확인함"}'::jsonb),
        ('current-relief-wednesday', 0, 2, TIME '12:30', 'AFTERNOON', 'WEEKDAY', '검사 결과를 듣고 안도했다.', '병원에서 정기 검사 결과가 정상이라는 설명을 들었다.', '걱정했던 문제가 아니어서 다행이다.', 'RELIEF', 9, '[{"code":"GRATITUDE","intensity":7}]'::jsonb, 'HEALTH', 'PROFESSIONAL', '{"bodyReaction":"긴장이 풀림","behavior":"가족에게 결과를 알림"}'::jsonb),
        ('current-anger-wednesday', 0, 2, TIME '19:00', 'EVENING', 'WEEKDAY', '업무가 갑자기 바뀌어 화가 났다.', '퇴근 직전에 담당 범위가 바뀌었다는 연락을 받았다.', '이런 변경은 절대로 있어서는 안 된다.', 'ANGER', 8, '[{"code":"FRUSTRATION","intensity":8}]'::jsonb, 'WORK', 'SUPERVISOR', '{"interpretation":"예상 밖 변경을 부당함으로 해석함","bodyReaction":"턱에 힘이 들어감","behavior":"답장을 바로 보내려다 멈춤"}'::jsonb),

        -- 한 주에 서로 다른 3일 밤 감사: RECENT 패턴
        ('current-gratitude-friday', 0, 4, TIME '21:10', 'NIGHT', 'WEEKDAY', '친구가 이야기를 들어줘 고마웠다.', '친구와 통화하며 힘들었던 일을 이야기했다.', '혼자 감당하지 않아도 된다.', 'GRATITUDE', 8, '[{"code":"RELIEF","intensity":6}]'::jsonb, 'RELATIONSHIP', 'FRIEND', '{"behavior":"고맙다는 메시지를 보냄"}'::jsonb),
        ('current-gratitude-saturday', 0, 5, TIME '22:00', 'NIGHT', 'WEEKEND', '편안한 하루를 보내 감사했다.', '가족과 저녁을 먹고 산책했다.', '평범한 시간이 큰 힘이 된다.', 'GRATITUDE', 7, '[{"code":"CALM","intensity":7}]'::jsonb, 'FAMILY', 'FAMILY', '{"behavior":"오늘 좋았던 일을 메모함"}'::jsonb),
        ('current-gratitude-sunday', 0, 6, TIME '21:30', 'NIGHT', 'WEEKEND', '한 주를 무사히 마쳐 감사했다.', '다음 주를 준비하며 한 주를 돌아봤다.', '완벽하지 않아도 충분히 해냈다.', 'GRATITUDE', 8, '[{"code":"ACHIEVEMENT","intensity":6}]'::jsonb, 'SELF_GROWTH', 'SELF', '{"behavior":"감사한 일 세 가지를 기록함"}'::jsonb),
        ('current-excitement-saturday', 0, 5, TIME '11:00', 'MORNING', 'WEEKEND', '새로운 전시를 보러 가서 설렜다.', '오랫동안 기다린 전시회에 방문했다.', '새로운 경험을 즐길 수 있겠다.', 'EXCITEMENT', 9, '[{"code":"JOY","intensity":8}]'::jsonb, 'LEISURE', 'FRIEND', '{"bodyReaction":"에너지가 올라옴","behavior":"사진을 찍고 감상을 나눔"}'::jsonb),
        ('current-anxiety-sunday', 0, 6, TIME '16:00', 'AFTERNOON', 'WEEKEND', '다음 주 지출이 걱정됐다.', '예상보다 카드 사용액이 많다는 것을 확인했다.', '이번 달 계획을 모두 망친 것 같다.', 'ANXIETY', 6, '[{"code":"REGRET","intensity":5}]'::jsonb, 'FINANCE', 'SELF', '{"bodyReaction":"속이 답답함","behavior":"지출 내역을 분류함"}'::jsonb)
)
INSERT INTO emotion_records (
    user_id,
    occurred_at,
    record_timezone,
    time_bucket,
    weekday_type,
    input_type,
    raw_text,
    situation_text,
    automatic_thought,
    primary_emotion_code,
    primary_intensity,
    secondary_emotions,
    context_category,
    related_person_type,
    details,
    completion_status,
    ai_meta,
    created_at,
    updated_at
)
SELECT
    seed_user.id,
    (
        context.report_week_start
        + emotion.week_offset * 7
        + emotion.day_offset
        + emotion.local_time
    ) AT TIME ZONE context.user_timezone,
    context.user_timezone,
    emotion.time_bucket,
    emotion.weekday_type,
    'TEXT',
    emotion.raw_text,
    emotion.situation_text,
    emotion.automatic_thought,
    emotion.primary_emotion_code,
    emotion.primary_intensity,
    emotion.secondary_emotions,
    emotion.context_category,
    emotion.related_person_type,
    emotion.details,
    'COMPLETE',
    jsonb_build_object(
        'seed', context.seed_name,
        'seedKey', emotion.seed_key,
        'model', 'dummy-weekly-report',
        'promptVersion', 'seed-v1'
    ),
    (
        context.report_week_start
        + emotion.week_offset * 7
        + emotion.day_offset
        + emotion.local_time
    ) AT TIME ZONE context.user_timezone,
    (
        context.report_week_start
        + emotion.week_offset * 7
        + emotion.day_offset
        + emotion.local_time
    ) AT TIME ZONE context.user_timezone
FROM emotion_seed emotion
CROSS JOIN weekly_report_seed_context context
JOIN users seed_user ON seed_user.email = context.user_email;

-- 선택 주에 완료·확정된 CBT 성찰 3건을 만든다.
WITH reflection_seed (
    emotion_seed_key,
    completed_day_offset,
    completed_time,
    evidence_for_text,
    evidence_against_text,
    alternative_thought_text,
    before_belief_strength,
    after_belief_strength,
    final_emotion_intensity,
    helpfulness_score
) AS (
    VALUES
        ('current-anxiety-monday', 0, TIME '10:00', '회의에서 질문을 받은 적이 있다.', '이전 발표는 무사히 마쳤고 동료들은 내용에 집중했다.', '긴장할 수 있지만 준비한 내용을 천천히 설명하면 된다.', 90, 45, 4, 4),
        ('current-sadness-monday', 1, TIME '08:00', '평소보다 답장이 늦었다.', '친구는 바쁜 날에는 늦게 답했고 관계가 멀어졌다는 증거는 없다.', '답장이 늦다는 사실만으로 내가 중요하지 않다고 단정할 수 없다.', 75, 40, 3, 4),
        ('current-anger-wednesday', 2, TIME '20:20', '퇴근 직전에 변경을 전달받았다.', '긴급한 사정이 있었고 담당자가 먼저 양해를 구했다.', '불편함을 표현하되 변경 이유와 가능한 일정을 차분히 확인할 수 있다.', 85, 30, 3, 5)
)
INSERT INTO reflection_sessions (
    user_id,
    emotion_record_id,
    status,
    current_step,
    question_answers,
    evidence_for_text,
    evidence_against_text,
    alternative_thought_text,
    before_belief_strength,
    after_belief_strength,
    final_emotion_intensity,
    helpfulness_score,
    user_confirmed,
    embedding_meta,
    ai_meta,
    created_at,
    completed_at,
    updated_at
)
SELECT
    seed_user.id,
    emotion_record.id,
    'COMPLETED',
    'COMPLETED',
    jsonb_build_array(
        jsonb_build_object(
            'questionCode', 'EVIDENCE_CHECK',
            'questionPurpose', '자동적 사고의 근거를 균형 있게 확인하기',
            'question', '그 생각을 뒷받침하거나 다르게 볼 수 있는 사실은 무엇인가요?',
            'answer', reflection.evidence_against_text,
            'askedAt', emotion_record.occurred_at,
            'answeredAt', emotion_record.occurred_at + INTERVAL '20 minutes'
        )
    ),
    reflection.evidence_for_text,
    reflection.evidence_against_text,
    reflection.alternative_thought_text,
    reflection.before_belief_strength,
    reflection.after_belief_strength,
    reflection.final_emotion_intensity,
    reflection.helpfulness_score,
    true,
    '{}'::jsonb,
    jsonb_build_object(
        'seed', context.seed_name,
        'model', 'dummy-cbt',
        'promptVersion', 'seed-v1'
    ),
    emotion_record.occurred_at + INTERVAL '10 minutes',
    (
        context.report_week_start
        + reflection.completed_day_offset
        + reflection.completed_time
    ) AT TIME ZONE context.user_timezone,
    (
        context.report_week_start
        + reflection.completed_day_offset
        + reflection.completed_time
    ) AT TIME ZONE context.user_timezone
FROM reflection_seed reflection
CROSS JOIN weekly_report_seed_context context
JOIN users seed_user ON seed_user.email = context.user_email
JOIN emotion_records emotion_record
  ON emotion_record.user_id = seed_user.id
 AND emotion_record.ai_meta ->> 'seedKey' = reflection.emotion_seed_key;

-- 인지왜곡 전후 변화: 제거, 유지, 신규 항목이 모두 나오도록 구성한다.
WITH distortion_seed (
    emotion_seed_key,
    phase,
    distortion_code,
    confidence
) AS (
    VALUES
        ('current-anxiety-monday', 'BEFORE', 'CATASTROPHIZING_FORTUNE_TELLING', 0.9100),
        ('current-anxiety-monday', 'BEFORE', 'MIND_READING', 0.8400),
        ('current-anxiety-monday', 'AFTER',  'MIND_READING', 0.6200),
        ('current-sadness-monday', 'BEFORE', 'DISQUALIFYING_DISCOUNTING_POSITIVE', 0.8700),
        ('current-anger-wednesday', 'BEFORE', 'SHOULD_MUST_STATEMENTS', 0.9300),
        ('current-anger-wednesday', 'AFTER',  'PERSONALIZATION', 0.5800)
)
INSERT INTO session_distortions (
    session_id,
    phase,
    distortion_type_id,
    source,
    review_status,
    classifier_confidence,
    reviewed_at,
    created_at
)
SELECT
    reflection_session.id,
    distortion.phase,
    distortion_type.id,
    'AI',
    'CONFIRMED',
    distortion.confidence,
    reflection_session.completed_at,
    reflection_session.created_at
FROM distortion_seed distortion
CROSS JOIN weekly_report_seed_context context
JOIN users seed_user ON seed_user.email = context.user_email
JOIN emotion_records emotion_record
  ON emotion_record.user_id = seed_user.id
 AND emotion_record.ai_meta ->> 'seedKey' = distortion.emotion_seed_key
JOIN reflection_sessions reflection_session
  ON reflection_session.emotion_record_id = emotion_record.id
JOIN distortion_types distortion_type
  ON distortion_type.code = distortion.distortion_code;

-- psql 실행 결과에서 바로 확인할 요약이다.
SELECT
    context.user_email AS login_email,
    'Password1!' AS login_password,
    context.report_week_start,
    context.report_week_start + 6 AS report_week_end,
    count(DISTINCT emotion_record.id) FILTER (
        WHERE emotion_record.occurred_at >= (
                  context.report_week_start::timestamp
                  AT TIME ZONE context.user_timezone
              )
          AND emotion_record.occurred_at < (
                  (context.report_week_start + 7)::timestamp
                  AT TIME ZONE context.user_timezone
              )
    ) AS selected_week_emotion_records,
    count(DISTINCT reflection_session.id) AS completed_cbt_sessions
FROM weekly_report_seed_context context
JOIN users seed_user ON seed_user.email = context.user_email
JOIN emotion_records emotion_record
  ON emotion_record.user_id = seed_user.id
 AND emotion_record.ai_meta ->> 'seed' = context.seed_name
LEFT JOIN reflection_sessions reflection_session
  ON reflection_session.emotion_record_id = emotion_record.id
 AND reflection_session.status = 'COMPLETED'
 AND reflection_session.user_confirmed = true
GROUP BY
    context.user_email,
    context.report_week_start,
    context.user_timezone;

COMMIT;
