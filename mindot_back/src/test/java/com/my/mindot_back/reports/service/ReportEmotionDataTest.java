package com.my.mindot_back.reports.service;

import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.reports.dto.EmotionCountDto;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReportEmotionDataTest {
    static EmotionRecords record(long id, String instant, String emotion, Short intensity, String context) {
        var r = mock(EmotionRecords.class);
        when(r.getId()).thenReturn(id); when(r.getOccurredAt()).thenReturn(Instant.parse(instant));
        when(r.getPrimaryEmotionCode()).thenReturn(emotion); when(r.getPrimaryIntensity()).thenReturn(intensity);
        when(r.getContextCategory()).thenReturn(context); when(r.getRawText()).thenReturn("검증 기록 " + id);
        return r;
    }
    @Test void allMonthLengthsMissingAndCustomValuesRemainCounted() {
        for (String text : List.of("2026-02", "2024-02", "2026-09", "2026-10")) {
            var month = YearMonth.parse(text); var zone = ZoneId.of("Asia/Seoul");
            int split = month.lengthOfMonth() / 2;
            var values = Arrays.asList("ANXIETY", "ANXIETY", "OTHER", null, "감정 미입력", "JOY 뒤의 허전함");
            List<EmotionRecords> records = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) records.add(record(i + 1,
                    month.atDay(i < 3 ? split : split + 1).atStartOfDay(zone).toInstant().toString(), values.get(i),
                    i % 2 == 0 ? (short)0 : null, i < 3 ? "WORK" : null));
            var c = ReportEmotionData.monthly(records, month, zone);
            assertThat(c.days()).hasSize(month.lengthOfMonth());
            assertThat(c.emotions().stream().mapToLong(EmotionCountDto::count).sum()).isEqualTo(6);
            assertThat(c.days().stream().mapToLong(g -> g.recordCount()).sum()).isEqualTo(6);
            assertThat(c.contexts().stream().mapToLong(g -> g.recordCount()).sum()).isEqualTo(6);
            assertThat(c.halves().stream().mapToLong(g -> g.recordCount()).sum()).isEqualTo(6);
            assertThat(c.halves().get(0).periodEnd()).isEqualTo(month.atDay(split).toString());
            assertThat(c.halves().get(1).periodStart()).isEqualTo(month.atDay(split + 1).toString());
            assertThat(c.emotions()).contains(new EmotionCountDto(null, 1), new EmotionCountDto("감정 미입력", 1), new EmotionCountDto("ANXIETY", 2));
            assertThat(c.days().get(0).recordCount()).isZero();
            for (var g : c.contexts()) assertThat(g.emotions().stream().mapToLong(EmotionCountDto::count).sum()).isEqualTo(g.recordCount());
        }
    }
    @Test void unequalHalfTotalsUseTheirOwnDenominators() {
        List<EmotionRecords> records = new ArrayList<>();
        for (int i = 0; i < 12; i++) records.add(record(i + 1, i < 2 ? "2026-09-01T00:00:00Z" : "2026-09-20T00:00:00Z",
                i % 2 == 0 ? "ANXIETY" : "JOY", null, "WORK"));
        var c = ReportEmotionData.monthly(records, YearMonth.of(2026, 9), ZoneId.of("Asia/Seoul"));
        assertThat(c.halves().get(0).recordCount()).isEqualTo(2);
        assertThat(c.halves().get(1).recordCount()).isEqualTo(10);
        for (var half : c.halves()) assertThat(ReportPdfCharts.proportion(half.emotions().get(0).count(), half.recordCount())).isEqualTo("50.0%");
    }
    @Test void localOccurrenceDayAndTimeBucketsMatchWeeklyRules() {
        var r = record(1, "2026-08-31T15:00:00Z", "짜증", (short)0, "WORK");
        var c = ReportEmotionData.monthly(List.of(r), YearMonth.of(2026, 9), ZoneId.of("Asia/Seoul"));
        assertThat(c.days().get(0).recordCount()).isEqualTo(1);
        assertThat(ReportEmotionData.radius((short)0)).isEqualTo(5);
        assertThat(ReportEmotionData.radius(null)).isEqualTo(5);
        assertThat(ReportEmotionData.radius((short)10)).isEqualTo(Math.sqrt(145));
        for (int h = 0; h < 24; h++) assertThat(ReportEmotionData.bucket(h)).isEqualTo(h < 6 ? 0 : h < 12 ? 1 : h < 18 ? 2 : h < 21 ? 3 : 4);
    }
    @Test void backendColorsAndLabelsExactlyMatchWebMapping() throws Exception {
        String colors = Files.readString(Path.of("../mindot_front/src/utils/records/emotionColors.js"));
        var matcher = Pattern.compile("([A-Z_]+|짜증): '(#[0-9a-f]{6})'").matcher(colors);
        Map<String, String> web = new LinkedHashMap<>(); while (matcher.find()) web.put(matcher.group(1), matcher.group(2));
        assertThat(ReportEmotionData.COLORS).isEqualTo(web);
        assertThat(ReportEmotionData.EMOTION_ORDER).containsExactlyElementsOf(web.keySet());
        String labels = Files.readString(Path.of("../mindot_front/src/utils/records/emotions.js"));
        for (var entry : ReportEmotionData.LABELS.entrySet()) assertThat(labels).contains(entry.getKey() + ": '" + entry.getValue() + "'");
        assertThat(ReportEmotionData.label("JOY 뒤의 허전함")).isEqualTo("JOY 뒤의 허전함");
    }
    @Test void crowdedScatterKeepsEveryNumberAndActualTimeWithoutOverlappingSameLane() {
        List<ReportPdfCharts.Point> points = new ArrayList<>();
        for (int i = 1; i <= 17; i++) {
            var r = record(i, "2026-09-21T03:00:00Z", "ANXIETY", (short)10, null);
            points.add(new ReportPdfCharts.Point(i, r, LocalDate.of(2026, 9, 21), 0, 720, 0));
        }
        var sheets = ReportPdfCharts.scatterSheets(points);
        assertThat(sheets).hasSize(9);
        assertThat(sheets.stream().flatMap(List::stream).map(ReportPdfCharts.Point::number).sorted().toList()).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,17).boxed().toList());
        for (var sheet : sheets) {
            assertThat(sheet).hasSizeLessThanOrEqualTo(2);
            assertThat(sheet).allMatch(p -> p.minute() == 720);
        }
    }
}
