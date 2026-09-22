package com.my.mindot_back.reports.service;

import com.my.mindot_back.records.entity.*;
import com.my.mindot_back.records.repository.*;
import com.my.mindot_back.reports.dto.*;
import com.my.mindot_back.reports.repository.ReportsRepository;
import com.my.mindot_back.reports.entity.Reports;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ReportPdfChartsTest {
    private final UsersRepository users = mock(UsersRepository.class);
    private final EmotionRecordsRepository records = mock(EmotionRecordsRepository.class);
    private final ReflectionSessionsRepository reflections = mock(ReflectionSessionsRepository.class);
    private final ReportsRepository reports = mock(ReportsRepository.class);
    private Users user() {
        var user = mock(Users.class);
        when(user.getTimezone()).thenReturn("Asia/Seoul");
        when(user.getDisplayName()).thenReturn("리포트 검증용 가상 사용자");
        when(user.getEmail()).thenReturn("report-test@example.invalid");
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(users.findLockedById(7L)).thenReturn(Optional.of(user));
        return user;
    }
    private void records(List<EmotionRecords> values) {
        when(records.findAllByUser_IdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAsc(any(), any(), any())).thenReturn(values);
    }
    private String inspect(String name, byte[] bytes) throws Exception {
        Path dir = Path.of("build/report-pdf-samples"); Files.createDirectories(dir);
        Files.write(dir.resolve(name + ".pdf"), bytes);
        try (var doc = Loader.loadPDF(bytes)) {
            var renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                ImageIO.write(renderer.renderImageWithDPI(i, 110), "png", dir.resolve(name + "-" + (i+1) + ".png").toFile());
            }
            String text = new PDFTextStripper().getText(doc);
            assertThat(text.replace("[U+코드]", "").replace("[U+1F600]", "")).doesNotContain("[U+");
            return text;
        }
    }
    @Test void selectedDatesAcrossWeeksIncludeOnlySelectedRecordsAndPreserveFullCbt() throws Exception {
        user(); List<EmotionRecords> values = new ArrayList<>();
        values.add(ReportEmotionDataTest.record(1, "2026-09-07T14:59:00Z", "짜증", (short)0, "WORK"));
        var excluded = ReportEmotionDataTest.record(2, "2026-09-14T00:00:00Z", "JOY", (short)5, "WORK");
        when(excluded.getRawText()).thenReturn("선택하지않은날짜의기록"); values.add(excluded);
        for (int i = 3; i <= 8; i++) values.add(ReportEmotionDataTest.record(i, "2026-09-20T15:00:00Z",
                i == 3 ? null : i == 4 ? "OTHER" : "JOY 뒤에 남아 있는 길고 복잡한 직접 입력 감정 이름", i % 2 == 0 ? null : (short)10, null));
        records(values);
        var cbt = mock(ReflectionSessions.class);
        when(cbt.getEmotionRecord()).thenReturn(values.get(0));
        when(cbt.getAlternativeThoughtText()).thenReturn("다른 관점으로 다시 살펴보았습니다.");
        when(cbt.getQuestionAnswers()).thenReturn(List.of(Map.of("role", "USER", "content", "긴 CBT 대화의 줄바꿈과 페이지 분할을 확인합니다. ".repeat(220) + "대화마지막문장")));
        when(reflections.findAllByUser_IdAndEmotionRecord_IdInAndStatusAndUserConfirmedTrueOrderByCompletedAtAsc(any(), any(), any())).thenReturn(List.of(cbt));
        var service = new PdfExportService(records, reflections, users);
        var dates = List.of(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 21));
        String text = inspect("weekly-selected-dates", service.exportPdf(7L, new PdfExportRequestDto(null, null, dates, ExportContentType.BOTH, true)));
        assertThat(text).contains("감정 기록 7건", "요일×발생 시각 감정 산점도", "요일별 기록 수", "시간대별 기록 수", "2026-09-21 00:00", "기록 #7", "대화마지막문장");
        assertThat(text).doesNotContain("선택하지않은날짜의기록", "2026-09-14 - 2026-09-20", "기록 #8");
        assertThat(sumMatches(text, "이 그래프 (\\d+)건")).isEqualTo(7);
        assertThat(sumMatches(text, "[월화수목금토일]요일 · (\\d+)건")).isEqualTo(7);
        assertThat(sumMatches(text, "(?:새벽|아침|오후|저녁|밤) \\d{2}:00-\\d{2}:00 · (\\d+)건")).isEqualTo(7);
        verify(records).findAllByUser_IdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAsc(7L, Instant.parse("2026-09-06T15:00:00Z"), Instant.parse("2026-09-21T15:00:00Z"));
        verify(reflections).findAllByUser_IdAndEmotionRecord_IdInAndStatusAndUserConfirmedTrueOrderByCompletedAtAsc(eq(7L), eq(List.of(1L,3L,4L,5L,6L,7L,8L)), eq(ReflectionSessionStatus.COMPLETED));
        String cbtText = inspect("weekly-cbt-only", service.exportPdf(7L, new PdfExportRequestDto(null, null, dates, ExportContentType.CBT_RESULTS, true)));
        assertThat(cbtText).contains("대화마지막문장").doesNotContain("감정 그래프", "요일×발생 시각", "요일별 기록 수", "기록 #1");
    }
    @Test void emotionOnlyPeriodWithinOneWeekRendersAllThreeChartsWithoutCbt() throws Exception {
        user(); records(List.of(ReportEmotionDataTest.record(1, "2026-09-21T09:00:00Z", "ANXIETY", null, "WORK")));
        var service = new PdfExportService(records, reflections, users);
        String text = inspect("weekly-one-week", service.exportPdf(7L, new PdfExportRequestDto(LocalDate.of(2026,9,21), LocalDate.of(2026,9,27), null, ExportContentType.EMOTION_RECORDS, false)));
        assertThat(text).contains("요일×발생 시각 감정 산점도", "요일별 기록 수", "시간대별 기록 수", "기록 #1").doesNotContain("대화 전체");
        verifyNoInteractions(reflections);
    }
    private long sumMatches(String text, String expression) {
        return java.util.regex.Pattern.compile(expression).matcher(text).results().mapToLong(m -> Long.parseLong(m.group(1))).sum();
    }
    @Test void monthlyThirtyOneDaysManyEmotionsAndLongCustomNamesRenderWithoutLegacyMetrics() throws Exception {
        var user = user();
        List<String> emotions = new ArrayList<>(ReportEmotionData.COLORS.keySet().stream().sorted().toList());
        emotions.add("기쁨과 허전함이 동시에 느껴져서 말로 표현하기 어려웠던 복잡하고 긴 감정 이름"); emotions.add("감정 미입력"); emotions.add(null);
        List<EmotionRecords> values = new ArrayList<>();
        for (int i = 0; i < emotions.size(); i++) values.add(ReportEmotionDataTest.record(i+1,
                LocalDate.of(2026,8,i+1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toString(), emotions.get(i), i%2 == 0 ? null : (short)7, i%3 == 0 ? null : "WORK"));
        records(values);
        when(reports.save(any(Reports.class))).thenAnswer(i -> i.getArgument(0));
        var monthly = new MonthlyReportsService(reports, records, reflections, users);
        var generated = monthly.generateMonthlyReport(7L, YearMonth.of(2026,8));
        var cachedService = mock(MonthlyReportsService.class);
        when(cachedService.getMonthlyReport(7L, YearMonth.of(2026,8))).thenReturn(generated);
        String text = inspect("monthly-31-days", new MonthlyReportPdfService(cachedService, users).exportMonthlyPdf(7L, YearMonth.of(2026,8)));
        assertThat(text).contains("날짜별 감정 기록 수", "기록한 상황", "월 초반·후반 비교", "2026-08-31", "평균 강도", "완료 CBT");
        assertThat(text).doesNotContain("평균 도움", "강도 입력일 평균", "월 전반 강도", "강도가 높아졌", "강도가 낮아졌");
        assertThat(text.replaceAll("\\s", "")).contains(emotions.get(emotions.size()-3).replaceAll("\\s", ""));
    }
}
