// 상담용 PDF 내보내기 데이터를 조회하고 PDF 파일을 생성하는 Service
package com.my.mindot_back.reports.service;

import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.ReflectionSessionStatus;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.reports.dto.ExportContentType;
import com.my.mindot_back.reports.dto.PdfExportRequestDto;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PdfExportService {

    // 선택 날짜의 감정 기록 조회
    private final EmotionRecordsRepository emotionRecordsRepository;

    // 선택 감정 기록에 연결된 완료 CBT 조회
    private final ReflectionSessionsRepository reflectionSessionsRepository;

    // 사용자 시간대와 소유자 확인
    private final UsersRepository usersRepository;

    // 기간 선택 또는 여러 날짜 선택을 목록으로 변환
    private List<LocalDate> resolveSelectedDates(
            PdfExportRequestDto dto
    ){
        boolean hasPeriodSelection =
                dto.startDate() != null || dto.endDate() != null;

        boolean hasDirectionSelection =
                dto.selectedDates() != null && !dto.selectedDates().isEmpty();

        // 기간 선택과 여러 날짜 선택 동시에 사용 불가
        if (hasPeriodSelection && hasDirectionSelection){
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "기간 선택 또는 날짜 직접 선택 중 하나만 사용할 수 있습니다."
            );
        }
        if (hasPeriodSelection) {
            if (dto.startDate() == null || dto.endDate() == null){
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "기간 선택 시 시작일과 종료일을 모두 입력해야 합니다."
                );
            }
            if (dto.endDate().isBefore(dto.startDate())){
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "종료일은 시작일보다 빠를 수 없습니다."
                );
            }

            if (ChronoUnit.DAYS.between(dto.startDate(), dto.endDate()) + 1 > 31){
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "PDF는 최대 31일까지 내보낼 수 있습니다."
                );
            }

            return dto.startDate()
                    .datesUntil(dto.endDate().plusDays(1))
                    .toList();
        }

        if (hasDirectionSelection) {
            List<LocalDate> selectedDates = dto.selectedDates().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList();

            if (selectedDates.size() > 31) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "PDF에 직접 선택할 수 있는 날짜는 최대 31개입니다."
                );
            }

            if (!selectedDates.isEmpty()){
                return selectedDates;
            }
        }

        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "내보낼 날짜를 하나 이상 선택해야 합니다."
        );
    }

    // PDF 내보내기를 요청한 사용자 조회
    private Users findUser(
            Long userId
    ){
        return usersRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "사용자를 찾을 수 없습니다."
                ));
    }

    // 선택 날짜에 실제로 발생한 감정 기록만 사용자 시간대 기준으로 조회
    private List<EmotionRecords> findSelectedEmotionRecords(
            Long userId,
            Users user,
            List<LocalDate> selectedDates
    ) {
        ZoneId zoneId = ZoneId.of(user.getTimezone());
        Set<LocalDate> selectedDateSet = Set.copyOf(selectedDates);

        LocalDate firstDate = selectedDates.get(0);
        LocalDate lastDate = selectedDates.get(selectedDates.size() - 1);

        Instant rangeStart = firstDate.atStartOfDay(zoneId).toInstant();
        var rangeEndExclusive = lastDate
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant();

        List<EmotionRecords> emotionRecords = emotionRecordsRepository
                .findAllByUser_IdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAsc(
                        userId,
                        rangeStart,
                        rangeEndExclusive
                )
                .stream()
                .filter(emotionRecord -> selectedDateSet.contains(
                        emotionRecord.getOccurredAt()
                                .atZone(zoneId)
                                .toLocalDate()
                ) )
                .toList();

        if (emotionRecords.isEmpty()){
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "선택한 날짜에 내보낼 감정 기록이 없습니다."
            );
        }
        return emotionRecords;
    }

    // 선택한 감정 기록에 연결된 완료, 확정 CBT 세션 조회
    private List<ReflectionSessions> findSelectedCompletedReflections(
            Long userId,
            List<EmotionRecords> emotionRecords
    ) {
        List<Long> emotionRecordIds = emotionRecords.stream()
                .map(EmotionRecords::getId)
                .toList();

        return reflectionSessionsRepository
                .findAllByUser_IdAndEmotionRecord_IdInAndStatusAndUserConfirmedTrueOrderByCompletedAtAsc(
                        userId,
                        emotionRecordIds,
                        ReflectionSessionStatus.COMPLETED
                );
    }

    // 선택한 감정 기록과 완료 CBT 결과를 상담용 PDF 바이트로 생성
    @Transactional(readOnly = true)
    public byte[] exportPdf(
            Long userId,
            PdfExportRequestDto dto
    ) {
        Users user = findUser(userId);
        List<LocalDate> selectedDates = resolveSelectedDates(dto);
        List<EmotionRecords> emotionRecords = findSelectedEmotionRecords(
                userId,
                user,
                selectedDates
        );

        List<ReflectionSessions> reflectionSessions =
                dto.contentType() == ExportContentType.EMOTION_RECORDS
                            ? List.of()
                            : findSelectedCompletedReflections(
                                    userId,
                                    emotionRecords
                             );
        try (
                PDDocument document = new PDDocument();
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream()
        ){
            PDType0Font koreaFont = loadKoreanFont(document);

            try (ReportPdfWriter writer = new ReportPdfWriter(
                    document,
                    koreaFont
            )){
                // PDF 첫 페이지 공통 제목과 사용자 정보 작성
                writer.writeCenteredTitle("MINDOT 상담용 기록", 22f);
                writer.addSpace(8f);
                writer.writeInfoRow("이름", user.getDisplayName());
                writer.writeInfoRow("이메일", user.getEmail());
                writer.writeInfoRow("선택 날짜", selectedDates.toString());
                writer.writeInfoRow(
                        "포함 내용",
                        (dto.contentType() == ExportContentType.CBT_RESULTS ? "연결 감정 기록 " : "감정 기록 ") + emotionRecords.size()
                                + "건 / 완료 CBT " + reflectionSessions.size() + "건"
                );
                writer.addSpace(22f);

                // 사용자가 감정 기록 포함을 선택한 경우에만 본문 작성
                if (dto.contentType() != ExportContentType.CBT_RESULTS) {
                    ReportPdfCharts.selectedRecords(writer, emotionRecords, selectedDates, ZoneId.of(user.getTimezone()));
                    writer.addPage();
                    writeEmotionRecordsSection(
                            writer,
                            emotionRecords,
                            ZoneId.of(user.getTimezone())
                    );
                }

                // 사용자가 CBT 결과 포함으로 선택한 경우에만 본문 작성
                if (dto.contentType() != ExportContentType.EMOTION_RECORDS) {
                    writeReflectionSessionsSection(
                            writer,
                            reflectionSessions,
                            ZoneId.of(user.getTimezone()),
                            dto.includeFullCbtConversation()
                    );
                }
            }
            document.save(outputStream);
            return outputStream.toByteArray();
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "PDF 파일 생성에 실패했습니다.",
                    exception
            );
        }
    }

    // 선택한 감정 기록을 상담용 PDF 본문에 작성
    private void writeEmotionRecordsSection(
            ReportPdfWriter writer,
            List<EmotionRecords> emotionRecords,
            ZoneId zoneId
    ) throws IOException {
        DateTimeFormatter dateTimeFormatter =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        writer.writeSectionTitle("감정 기록");
        writer.addSpace(6f);

        int recordNumber = 0;
        for (EmotionRecords emotionRecord : emotionRecords) {
            writer.writeSectionTitle("기록 #" + (++recordNumber));
            String occurredAt = emotionRecord.getOccurredAt()
                    .atZone(zoneId)
                    .format(dateTimeFormatter);

            String primaryEmotion = ReportEmotionData.label(emotionRecord.getPrimaryEmotionCode());
            String intensity = emotionRecord.getPrimaryIntensity() == null
                    ? "미입력"
                    : emotionRecord.getPrimaryIntensity() + "/10";

            writer.writeInfoRow("발생 일시", occurredAt);
            writer.writeInfoRow(
                    "주 감정 / 강도",
                    primaryEmotion + " / " + intensity
            );
            writer.addSpace(14f);
            writer.writeAccentLine("원문", 10f);
            writer.writeParagraph(emotionRecord.getRawText(), 10f);

            if (emotionRecord.getSituationText() != null
                    && !emotionRecord.getSituationText().isBlank()) {
                writer.writeAccentLine("상황", 10f);
                writer.writeParagraph(emotionRecord.getSituationText(), 10f);
            }

            if (emotionRecord.getAutomaticThought() != null
                    && !emotionRecord.getAutomaticThought().isBlank()) {
                writer.writeAccentLine("자동적 사고", 10f);
                writer.writeParagraph(emotionRecord.getAutomaticThought(), 10f);
            }

            writer.addSpace(12f);
        }
    }

    // 완료, 확정된 CBT 성찰 결과를 상담용 PDF 본문에 작성
    private void writeReflectionSessionsSection(
            ReportPdfWriter writer,
            List<ReflectionSessions> reflectionSessions,
            ZoneId zoneId,
            boolean includeFullCbtConversation
    ) throws IOException {
        DateTimeFormatter dateTimeFormatter =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        writer.writeSectionTitle("완료 CBT 성찰 결과");
        writer.writeParagraph("이 PDF는 감정 발생일로 선택한 기록에 연결된 CBT를 포함합니다. 주간 리포트의 CBT 완료일 기준과 다를 수 있습니다.", 9f);
        writer.addSpace(6f);

        if (reflectionSessions.isEmpty()) {
            writer.writeLine(
                    "선택한 감정 발생일의 기록에 연결된 완료 CBT 성찰 결과가 없습니다.",
                    10f
            );
            return;
        }

        for (ReflectionSessions reflectionSession : reflectionSessions) {
            EmotionRecords emotionRecord = reflectionSession.getEmotionRecord();
            String occurredAt = emotionRecord.getOccurredAt()
                    .atZone(zoneId)
                    .format(dateTimeFormatter);
            String primaryEmotion = ReportEmotionData.label(emotionRecord.getPrimaryEmotionCode());
            String intensity = emotionRecord.getPrimaryIntensity() == null
                    ? "미입력"
                    : emotionRecord.getPrimaryIntensity() + "/10";

            // CBT가 시작된 감정 기록의 맥락을 함께 표시
            writer.writeAccentLine("연결 감정 기록", 10f);
            writer.writeParagraph(
                    occurredAt + " / " + primaryEmotion + " / " + intensity,
                    10f
            );
            writer.writeParagraph(
                    emotionRecord.getRawText(),
                    10f
            );

            writeCbtTextIfPresent(
                    writer,
                    "처음 생각을 뒷받침하는 근거",
                    reflectionSession.getEvidenceForText()
            );
            writeCbtTextIfPresent(
                    writer,
                    "처음 생각과 다른 근거",
                    reflectionSession.getEvidenceAgainstText()
            );
            writeCbtTextIfPresent(
                    writer,
                    reflectionSession.confirmedInsight()==null?"대안적 사고 (구형 결과)":"알아차리고 수정한 생각",
                    reflectionSession.getAlternativeThoughtText()
            );
            writeCbtTextIfPresent(writer,"처음 생각",reflectionSession.confirmedBeforeText());
            var confirmed=reflectionSession.confirmedInsight();
            if(confirmed!=null) {
                writeCbtTextIfPresent(
                        writer,
                        "생각을 수정한 이유",
                        stringValue(confirmed.get("comparisonExplanation"))
                );
                writeConfirmedSuggestions(writer, confirmed);
            }
            writer.writeLine(
                    "같은 처음 생각에 대한 확신도: "
                            + scoreOrDash(reflectionSession.getBeforeBeliefStrength())
                            + " → "
                            + scoreOrDash(reflectionSession.getAfterBeliefStrength()),
                    10f
            );
            writer.addSpace(6f);
            writer.writeLine(
                    "최종 감정 강도: "
                            + scoreOrDash(reflectionSession.getFinalEmotionIntensity())
                            + "/10 / 도움 점수: "
                            + scoreOrDash(reflectionSession.getHelpfulnessScore())
                            + "/5",
                    10f
            );

            if (includeFullCbtConversation) {
                writeFullCbtConversation(
                        writer,
                        reflectionSession.getQuestionAnswers()
                );
            }

            writer.addSpace(12f);
        }
    }

    // CBT 결과의 텍스트 값이 있을 때만 강조 제목과 함께 작성
    private void writeCbtTextIfPresent(
            ReportPdfWriter writer,
            String label,
            String text
    ) throws IOException {
        if (text != null && !text.isBlank()) {
            writer.writeAccentLine(label, 10f);
            writer.writeParagraph(text, 10f);
        }
    }

    // CBT 결과 형식이 달라도 유효한 제안만 PDF에 포함
    private void writeConfirmedSuggestions(
            ReportPdfWriter writer,
            Map<String, Object> confirmed
    ) throws IOException {
        Set<String> confirmedCodes = confirmedCodes(
                confirmed.get("reviews")
        );
        Object suggestionsValue = confirmed.get("suggestions");

        if (!(suggestionsValue instanceof List<?> suggestions)) {
            return;
        }

        for (Object suggestionValue : suggestions) {
            if (!(suggestionValue instanceof Map<?, ?> suggestion)) {
                continue;
            }

            String code = stringValue(suggestion.get("code"));
            String explanation = stringValue(
                    suggestion.get("explanation")
            );

            if (code == null || code.isBlank()) {
                continue;
            }

            writeCbtTextIfPresent(
                    writer,
                    code + (confirmedCodes.contains(code)
                            ? " (수락)"
                            : " (거부)"),
                    explanation
            );
        }
    }

    // 사용자 검토 목록에서 수락한 인지왜곡 코드만 안전하게 추출
    private Set<String> confirmedCodes(Object reviewsValue) {
        if (!(reviewsValue instanceof List<?> reviews)) {
            return Set.of();
        }

        java.util.HashSet<String> codes = new java.util.HashSet<>();

        for (Object reviewValue : reviews) {
            if (!(reviewValue instanceof Map<?, ?> review)) {
                continue;
            }

            String code = stringValue(review.get("code"));
            String reviewStatus = stringValue(
                    review.get("reviewStatus")
            );

            if (code != null && "CONFIRMED".equals(reviewStatus)) {
                codes.add(code);
            }
        }

        return Set.copyOf(codes);
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text : null;
    }

    // 점수가 없는 경우 PDF에 "-"로 표시
    private String scoreOrDash(
            Short score
    ) {
        return score == null ? "-" : score.toString();
    }

    // 사용자가 선택한 경우에만 CBT 질문, 답변 전체를 작성
    private void writeFullCbtConversation(
            ReportPdfWriter writer,
            List<Map<String, Object>> questionAnswers
    ) throws IOException {
        writer.writeAccentLine("대화 전체", 11f);
        writer.addSpace(4f);

        if (questionAnswers == null) {
            return;
        }

        for (Map<String, Object> questionAnswer : questionAnswers) {
            if(questionAnswer.get("content") instanceof String content && questionAnswer.get("role") instanceof String role) {
                writer.writeConversationBlock("USER".equals(role)?"사용자":"AI",content);
                writer.addSpace(5f);continue;
            }
            Object question = questionAnswer.get("question");
            Object answer = questionAnswer.get("answer");

            if (question instanceof String questionText
                    && !questionText.isBlank()) {
                writer.writeConversationBlock("AI", questionText);
            }

            if (answer instanceof String answerText
                    && !answerText.isBlank()) {
                writer.writeConversationBlock("사용자", answerText);
            }

            writer.addSpace(5f);
        }
    }

    // 배포된 JAR 내부의 한글 폰트를 PDF 문서에 로드
    private PDType0Font loadKoreanFont(
            PDDocument document
    ) throws IOException {
        ClassPathResource fontResource = new ClassPathResource(
                "fonts/NanumGothic-Regular.ttf"
        );

        try (InputStream inputStream = fontResource.getInputStream()) {
            return PDType0Font.load(
                    document,
                    inputStream,
                    true  // 실제 사용된 글자만 포함하도록
            );
        }
    }

}
