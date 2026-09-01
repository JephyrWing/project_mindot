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
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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

            try (PdfPageWriter writer = new PdfPageWriter(
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
                        "감정 기록 " + emotionRecords.size()
                                + "건 / 완료 CBT " + reflectionSessions.size() + "건"
                );
                writer.addSpace(22f);

                // 사용자가 감정 기록 포함을 선택한 경우에만 본문 작성
                if (dto.contentType() != ExportContentType.CBT_RESULTS) {
                    writeEmotionRecordsSection(
                            writer,
                            emotionRecords,
                            ZoneId.of(user.getTimezone())
                    );
                }

                // 사용자가 CBT 결과 포함으 선택한 경우에만 본문 작성
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
            PdfPageWriter writer,
            List<EmotionRecords> emotionRecords,
            ZoneId zoneId
    ) throws IOException {
        DateTimeFormatter dateTimeFormatter =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        writer.writeSectionTitle("감정 기록");
        writer.addSpace(6f);

        for (EmotionRecords emotionRecord : emotionRecords) {
            String occurredAt = emotionRecord.getOccurredAt()
                    .atZone(zoneId)
                    .format(dateTimeFormatter);

            String primaryEmotion = emotionRecord.getPrimaryEmotionCode() == null
                    ? "-"
                    : emotionRecord.getPrimaryEmotionCode();
            String intensity = emotionRecord.getPrimaryIntensity() == null
                    ? "-"
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
            PdfPageWriter writer,
            List<ReflectionSessions> reflectionSessions,
            ZoneId zoneId,
            boolean includeFullCbtConversation
    ) throws IOException {
        DateTimeFormatter dateTimeFormatter =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        writer.writeSectionTitle("완료 CBT 성찰 결과");
        writer.addSpace(6f);

        if (reflectionSessions.isEmpty()) {
            writer.writeLine(
                    "선택한 날짜에 완료, 확정된 CBT 성찰 결과가 없습니다.",
                    10f
            );
            return;
        }

        for (ReflectionSessions reflectionSession : reflectionSessions) {
            EmotionRecords emotionRecord = reflectionSession.getEmotionRecord();
            String occurredAt = emotionRecord.getOccurredAt()
                    .atZone(zoneId)
                    .format(dateTimeFormatter);
            String primaryEmotion = emotionRecord.getPrimaryEmotionCode() == null
                    ? "-"
                    : emotionRecord.getPrimaryEmotionCode();
            String intensity = emotionRecord.getPrimaryIntensity() == null
                    ? "-"
                    : emotionRecord.getPrimaryIntensity() + "/10";

            // CBT가 시작된 감정 기록의 맥락을 함께 표시
            writer.writeAccentLine("연결 감정 기록", 10f);
            writer.writeLine(
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
                    "대안적 사고",
                    reflectionSession.getAlternativeThoughtText()
            );
            writer.writeLine(
                    "생각 확신도: "
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
            PdfPageWriter writer,
            String label,
            String text
    ) throws IOException {
        if (text != null && !text.isBlank()) {
            writer.writeAccentLine(label, 10f);
            writer.writeParagraph(text, 10f);
        }
    }

    // 점수가 없는 경우 PDF에 "-"로 표시
    private String scoreOrDash(
            Short score
    ) {
        return score == null ? "-" : score.toString();
    }

    // 사용자가 선택한 경우에만 CBT 질문, 답변 전체를 작성
    private void writeFullCbtConversation(
            PdfPageWriter writer,
            List<Map<String, Object>> questionAnswers
    ) throws IOException {
        writer.writeAccentLine("대화 전체", 11f);
        writer.addSpace(4f);

        for (Map<String, Object> questionAnswer : questionAnswers) {
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

    // A4 PDF에 제목과 긴 문장을 페이지 단위로 작성하는 내부 도구
    private static class PdfPageWriter implements AutoCloseable {

        private static final float PAGE_MARGIN = 60f;
        private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
        private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
        private static final float INFO_LABEL_WIDTH = 110f;
        private static final float INFO_ROW_HEIGHT = 25f;

        private final PDDocument document;
        private final PDType0Font font;
        private PDPageContentStream contentStream;
        private float cursorY;

        private PdfPageWriter(
                PDDocument document,
                PDType0Font font
        ) throws IOException {
            this.document = document;
            this.font = font;
            addPage();
        }

        // 새 A4 페이지를 만들고 이전 페이지 작성 스트림을 닫음
        private void addPage() throws IOException {
            if (contentStream != null) {
                contentStream.close();
            }

            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            contentStream = new PDPageContentStream(document, page);
            cursorY = PAGE_HEIGHT - PAGE_MARGIN;
        }

        // 페이지 부족시 다음 페이지 만든 뒤 작성
        private void writeLine(
                String text,
                float fontSize
        ) throws IOException {
            if (cursorY - fontSize < PAGE_MARGIN) {
                addPage();
            }

            contentStream.beginText();
            contentStream.setFont(font, fontSize);
            contentStream.newLineAtOffset(PAGE_MARGIN, cursorY);
            contentStream.showText(text);
            contentStream.endText();

            cursorY -= fontSize + 9f;
        }

        // 문서 첫 제목을 페이지 가운데에 크게 작성
        private void writeCenteredTitle(
                String text,
                float fontSize
        ) throws IOException {
            if (cursorY - fontSize < PAGE_MARGIN) {
                addPage();
            }

            float textWidth = font.getStringWidth(text) / 1000 * fontSize;
            float startX = (PAGE_WIDTH - textWidth) / 2;

            contentStream.beginText();
            contentStream.setNonStrokingColor(
                    20f / 255f,
                    54f / 255f,
                    104f / 255f
            );
            contentStream.setFont(font, fontSize);
            contentStream.newLineAtOffset(startX, cursorY);
            contentStream.showText(text);
            contentStream.endText();

            contentStream.setNonStrokingColor(0, 0, 0);
            cursorY -= fontSize + 15f;
        }

        // 파란 막대와 함께 본문 섹션 제목을 작성
        private void writeSectionTitle(String text) throws IOException {
            float barHeight = 18f;

            if (cursorY - barHeight < PAGE_MARGIN) {
                addPage();
            }

            contentStream.setNonStrokingColor(
                    35f / 255f,
                    99f / 255f,
                    190f / 255f
            );
            contentStream.addRect(PAGE_MARGIN, cursorY - 15f, 4f, barHeight);
            contentStream.fill();

            contentStream.beginText();
            contentStream.setNonStrokingColor(
                    20f / 255f,
                    25f / 255f,
                    35f / 255f
            );
            contentStream.setFont(font, 15f);
            contentStream.newLineAtOffset(PAGE_MARGIN + 12f, cursorY - 11f);
            contentStream.showText(text);
            contentStream.endText();

            contentStream.setNonStrokingColor(0, 0, 0);
            cursorY -= 27f;
        }

        // CBT 결과의 제목을 파란색으로 강조해 작성
        private void writeAccentLine(
                String text,
                float fontSize
        ) throws IOException {
            if (cursorY - fontSize < PAGE_MARGIN) {
                addPage();
            }

            contentStream.beginText();
            contentStream.setNonStrokingColor(
                    25f / 255f,
                    76f / 255f,
                    145f / 255f
            );
            contentStream.setFont(font, fontSize);
            contentStream.newLineAtOffset(PAGE_MARGIN, cursorY);
            contentStream.showText(text);
            contentStream.endText();

            contentStream.setNonStrokingColor(0, 0, 0);
            cursorY -= fontSize + 9f;
        }

        // AI 질문과 사용자 답변을 각각 테두리 블록으로 작성
        private void writeConversationBlock(
                String speaker,
                String text
        ) throws IOException {
            float speakerWidth = 54f;
            float tableWidth = PAGE_WIDTH - PAGE_MARGIN * 2;
            float textWidth = tableWidth - speakerWidth - 14f;
            List<String> lines = wrapText(text, 9f, textWidth);
            float rowHeight = Math.max(22f, lines.size() * 14f + 10f);

            if (cursorY - rowHeight < PAGE_MARGIN) {
                addPage();
            }

            float rowBottom = cursorY - rowHeight;

            contentStream.setNonStrokingColor(
                    241f / 255f,
                    245f / 255f,
                    249f / 255f
            );
            contentStream.addRect(PAGE_MARGIN, rowBottom, speakerWidth, rowHeight);
            contentStream.fill();

            contentStream.setStrokingColor(
                    70f / 255f,
                    113f / 255f,
                    175f / 255f
            );
            contentStream.addRect(PAGE_MARGIN, rowBottom, tableWidth, rowHeight);
            contentStream.moveTo(PAGE_MARGIN + speakerWidth, rowBottom);
            contentStream.lineTo(PAGE_MARGIN + speakerWidth, cursorY);
            contentStream.stroke();

            contentStream.beginText();
            contentStream.setNonStrokingColor(
                    25f / 255f,
                    76f / 255f,
                    145f / 255f
            );
            contentStream.setFont(font, 9f);
            contentStream.newLineAtOffset(PAGE_MARGIN + 10f, cursorY - 15f);
            contentStream.showText(speaker + ":");
            contentStream.endText();

            contentStream.beginText();
            contentStream.setNonStrokingColor(0, 0, 0);
            contentStream.setFont(font, 9f);
            for (int index = 0; index < lines.size(); index++) {
                contentStream.newLineAtOffset(
                        index == 0 ? PAGE_MARGIN + speakerWidth + 7f : 0,
                        index == 0 ? cursorY - 15f : -14f
                );
                contentStream.showText(lines.get(index));
            }
            contentStream.endText();

            cursorY -= rowHeight;
        }

        // 텍스트 없이 세로 여백만 추가
        private void addSpace(float space) throws IOException {
            if (cursorY - space < PAGE_MARGIN) {
                addPage();
            }

            cursorY -= space;
        }

        // 상담용 식별 정보를 2열 표 한 행으로 작성
        private void writeInfoRow(
                String label,
                String value
        ) throws IOException {
            float tableWidth = PAGE_WIDTH - PAGE_MARGIN * 2;
            float valueWidth = tableWidth - INFO_LABEL_WIDTH - 14f;
            List<String> valueLines = wrapText(value, 10f, valueWidth);
            float rowHeight = Math.max(
                    INFO_ROW_HEIGHT,
                    valueLines.size() * 14f + 10f
            );

            if (cursorY - rowHeight < PAGE_MARGIN) {
                addPage();
            }

            float rowBottom = cursorY - rowHeight;

            // 왼쪽 항목명 칸의 연한 배경
            contentStream.setNonStrokingColor(
                    241f / 255f,
                    245f / 255f,
                    249f / 255f
            );
            contentStream.addRect(
                    PAGE_MARGIN,
                    rowBottom,
                    INFO_LABEL_WIDTH,
                    rowHeight
            );
            contentStream.fill();

            // 표 외곽선과 가운데 구분선
            contentStream.setStrokingColor(
                    196f / 255f,
                    207f / 255f,
                    222f / 255f
            );
            contentStream.addRect(
                    PAGE_MARGIN,
                    rowBottom,
                    tableWidth,
                    rowHeight
            );
            contentStream.moveTo(PAGE_MARGIN + INFO_LABEL_WIDTH, rowBottom);
            contentStream.lineTo(PAGE_MARGIN + INFO_LABEL_WIDTH, cursorY);
            contentStream.stroke();

            // 항목명 텍스트
            contentStream.beginText();
            contentStream.setNonStrokingColor(
                    35f / 255f,
                    59f / 255f,
                    99f / 255f
            );
            contentStream.setFont(font, 10f);
            contentStream.newLineAtOffset(PAGE_MARGIN + 7f, cursorY - 17f);
            contentStream.showText(label);
            contentStream.endText();

            // 값 텍스트를 표 폭에 맞춰 여러 줄로 작성
            contentStream.beginText();
            contentStream.setNonStrokingColor(0, 0, 0);
            contentStream.setFont(font, 10f);
            for (int index = 0; index < valueLines.size(); index++) {
                contentStream.newLineAtOffset(
                        index == 0
                                ? PAGE_MARGIN + INFO_LABEL_WIDTH + 7f
                                : 0,
                        index == 0 ? cursorY - 17f : -14f
                );
                contentStream.showText(valueLines.get(index));
            }
            contentStream.endText();

            cursorY -= rowHeight;
        }

        // PDF 가로 폭에 맞춰 긴 문장을 여러 줄로 분리
        private List<String> wrapText(
                String text,
                float fontSize
        ) throws IOException {
            return wrapText(
                    text,
                    fontSize,
                    PAGE_WIDTH - PAGE_MARGIN * 2
            );
        }

        // 지정한 가로 폭에 맞춰 긴 문장을 여러 줄로 분리
        private List<String> wrapText(
                String text,
                float fontSize,
                float maxWidth
        ) throws IOException {
            List<String> lines = new ArrayList<>();

            if (text == null || text.isBlank()) {
                return lines;
            }

            StringBuilder currentLine = new StringBuilder();

            for (char character : text.replace("\r", "").toCharArray()) {
                if (character == '\n') {
                    lines.add(currentLine.toString());
                    currentLine.setLength(0);
                    continue;
                }

                String candidate = currentLine.toString() + character;
                float candidateWidth =
                        font.getStringWidth(candidate) / 1000 * fontSize;

                if (candidateWidth > maxWidth && currentLine.length() > 0) {
                    lines.add(currentLine.toString());
                    currentLine.setLength(0);
                }

                currentLine.append(character);
            }

            if (currentLine.length() > 0) {
                lines.add(currentLine.toString());
            }

            return lines;
        }

        // 긴 문장을 줄바꿈한 뒤 페이지 단위로 작성
        private void writeParagraph(
                String text,
                float fontSize
        ) throws IOException {
            for (String line : wrapText(text, fontSize)) {
                writeLine(line, fontSize);
            }
        }

        // 마지막 페이지의 작성 스트림 닫기
        @Override
        public  void close() throws IOException{
            if (contentStream != null) {
                contentStream.close();
            }
        }
    }
}
