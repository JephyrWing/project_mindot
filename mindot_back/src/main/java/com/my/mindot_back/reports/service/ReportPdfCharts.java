package com.my.mindot_back.reports.service;

import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.reports.dto.EmotionCountDto;
import com.my.mindot_back.reports.dto.MonthlyEmotionCompositionDto;
import java.io.IOException;
import java.awt.Color;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Charts consume the same snapshot as the report body, without querying records again. */
final class ReportPdfCharts {
    private ReportPdfCharts() {}
    static final String[] DAYS = {"월", "화", "수", "목", "금", "토", "일"};
    static final String[] BUCKETS = {"새벽 00:00-06:00", "아침 06:00-12:00", "오후 12:00-18:00", "저녁 18:00-21:00", "밤 21:00-24:00"};
    static final float LEFT = ReportPdfWriter.PAGE_MARGIN;
    static final float WIDTH = ReportPdfWriter.PAGE_WIDTH - LEFT * 2;

    static String proportion(long count, long total) {
        return String.format(Locale.ROOT, "%.1f%%", total == 0 ? 0d : 100d * count / total);
    }
    static void legend(ReportPdfWriter w, List<EmotionCountDto> counts, long total) throws IOException {
        if (total == 0) { w.writeParagraph("기록 없음", 10); return; }
        for (var item : counts) {
            if (w.cursorY - 38 < ReportPdfWriter.PAGE_MARGIN) {
                w.addPage();
                w.writeParagraph("감정 범례 (이어짐)", 9);
            }
            w.rectangle(LEFT, w.cursorY - 1, 8, 8, ReportEmotionData.color(item.emotion()));
            // Paged, wrapped rows preserve long custom labels and every small segment.
            w.writeParagraph("    " + ReportEmotionData.label(item.emotion()) + " · " + item.count() + "건 · " + proportion(item.count(), total), 9);
        }
    }
    static List<EmotionCountDto> ordered(List<EmotionCountDto> counts, List<EmotionCountDto> order) {
        return order.stream().map(key -> counts.stream().filter(c -> Objects.equals(c.emotion(), key.emotion())).findFirst().orElse(null))
                .filter(Objects::nonNull).toList();
    }
    static void stacked(ReportPdfWriter w, String title, long total, List<EmotionCountDto> counts,
                        long scale, List<EmotionCountDto> order) throws IOException {
        w.ensureSpace(75);
        w.writeParagraph(title + " · " + total + "건", 10);
        float y = w.cursorY - 12;
        w.rectangle(LEFT, y, WIDTH, 12, new Color(239, 243, 248));
        float x = LEFT;
        for (var c : ordered(counts, order)) {
            float width = scale == 0 ? 0 : WIDTH * c.count() / scale;
            w.rectangle(x, y, width, 12, ReportEmotionData.color(c.emotion())); x += width;
        }
        w.cursorY = y - 18;
        legend(w, ordered(counts, order), total);
        w.addSpace(8);
    }
    static void daily(ReportPdfWriter w, MonthlyEmotionCompositionDto c) throws IOException {
        w.ensureSpace(280);
        w.writeSectionTitle("날짜별 감정 기록 수");
        w.writeParagraph("세로축: 기록 건수(건) · 가로축: 해당 월의 모든 날짜(일)", 9);
        float top = w.cursorY - 10, height = 155, bottom = top - height, left = LEFT + 24, width = WIDTH - 24;
        long max = Math.max(1, c.days().stream().mapToLong(MonthlyEmotionCompositionDto.Group::recordCount).max().orElse(0));
        long step = Math.max(1, (long)Math.ceil(max / 4d));
        Set<Long> ticks = new TreeSet<>();
        for (long tick = 0; tick <= max; tick += step) ticks.add(tick);
        ticks.add(max);
        for (long tick : ticks) {
            float y = bottom + height * tick / max;
            w.line(left, y, LEFT + WIDTH, y);
            w.writeCellText(Long.toString(tick), LEFT, y - 3, 7);
        }
        float cell = width / c.days().size();
        for (int i = 0; i < c.days().size(); i++) {
            float y = bottom;
            var day = c.days().get(i);
            for (var count : ordered(day.emotions(), c.emotions())) {
                float h = height * count.count() / max;
                w.rectangle(left + cell * i + 2, y, cell - 4, h, ReportEmotionData.color(count.emotion())); y += h;
            }
            w.writeCellText(Integer.toString(i + 1), left + cell * i + 2, bottom - 14, 7);
        }
        w.cursorY = bottom - 36;
        w.writeParagraph("감정별 색상 범례 · 월 전체 " + c.recordCount() + "건", 10);
        legend(w, c.emotions(), c.recordCount());
        w.writeSectionTitle("날짜별 상세 · 각 날짜의 기록 수 기준 비율");
        for (var day : c.days()) {
            w.writeInfoRow(day.value() + " · " + day.recordCount() + "건", description(ordered(day.emotions(), c.emotions()), day.recordCount()));
        }
        w.addSpace(18);
    }
    static String description(List<EmotionCountDto> counts, long total) {
        return total == 0 ? "기록 없음" : String.join(" · ", counts.stream()
                .map(c -> ReportEmotionData.label(c.emotion()) + " " + c.count() + "건 (" + proportion(c.count(), total) + ")").toList());
    }

    record Point(int number, EmotionRecords record, LocalDate date, int day, double minute, int lane) {
        float radius() { return (float)ReportEmotionData.radius(record.getPrimaryIntensity()); }
        float y() { return (float)(minute / 1440 * 460); }
    }
    // At most two horizontal lanes per day. A crowded point moves to another sheet,
    // never to another time and never into an aggregate mark.
    static List<List<Point>> scatterSheets(List<Point> points) {
        List<List<Point>> sheets = new ArrayList<>();
        for (var p : points) {
            boolean placed = false;
            for (var sheet : sheets) {
                for (int lane = 0; lane < 2 && !placed; lane++) {
                    int candidateLane = lane;
                    boolean free = sheet.stream().noneMatch(other -> other.day() == p.day() && other.lane() == candidateLane
                            && Math.abs(other.y() - p.y()) < other.radius() + p.radius() + 5);
                    if (free) { sheet.add(new Point(p.number(), p.record(), p.date(), p.day(), p.minute(), lane)); placed = true; }
                }
                if (placed) break;
            }
            if (!placed) { var sheet = new ArrayList<Point>(); sheet.add(p); sheets.add(sheet); }
        }
        if (sheets.isEmpty()) sheets.add(List.of());
        return sheets;
    }
    static void selectedRecords(ReportPdfWriter w, List<EmotionRecords> records, List<LocalDate> selectedDates, ZoneId zone) throws IOException {
        var emotions = ReportEmotionData.counts(records);
        w.writeSectionTitle("감정 그래프 · 선택 날짜 기준");
        w.writeParagraph("총 " + records.size() + "건 · 발생 시각 기준 · 시간대 " + zone + ". 선택하지 않은 날짜는 집계하지 않습니다.", 10);
        w.writeParagraph("각 점의 숫자는 상세 본문의 기록 번호입니다. 점 면적은 강도 0-10에 따라 증가합니다. 강도 0은 작은 채운 점, 미입력은 테두리 원입니다.", 9);
        legend(w, emotions, records.size());
        var weeks = new TreeMap<LocalDate, List<Point>>();
        for (var date : selectedDates) weeks.putIfAbsent(date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), new ArrayList<>());
        for (int i = 0; i < records.size(); i++) {
            var r = records.get(i); var time = r.getOccurredAt().atZone(zone); var date = time.toLocalDate();
            weeks.get(date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
                    .add(new Point(i + 1, r, date, time.getDayOfWeek().getValue() - 1, time.getHour() * 60 + time.getMinute(), 0));
        }
        for (var week : weeks.entrySet()) {
            var sheets = scatterSheets(week.getValue());
            for (int index = 0; index < sheets.size(); index++) {
                var sheet = sheets.get(index);
                w.addPage();
                w.writeSectionTitle("요일×발생 시각 감정 산점도");
                w.writeParagraph(week.getKey() + " - " + week.getKey().plusDays(6) + " · " + (index + 1) + "/" + sheets.size() + " · 이 그래프 " + sheet.size() + "건", 10);
                w.writeParagraph("시간대 " + zone + " · 아래 숫자는 문서 내 기록 번호 · 선택 날짜만 표시", 9);
                float left = LEFT + 40, width = WIDTH - 40, top = w.cursorY - 45;
                for (int d = 0; d < 7; d++) {
                    float center = left + width * (d + .5f) / 7;
                    w.writeCellText(DAYS[d], center - 4, top + 34, 9);
                    w.writeCellText(week.getKey().plusDays(d).format(DateTimeFormatter.ofPattern("MM/dd")), center - 12, top + 20, 7);
                }
                for (int hour = 0; hour <= 24; hour += 4) {
                    float y = top - hour / 24f * 460;
                    w.line(left, y, left + width, y);
                    w.writeCellText(String.format("%02d:00", hour), LEFT, y - 3, 8);
                }
                for (var p : sheet) {
                    float center = left + width * (p.day() + .5f) / 7;
                    float x = center + (p.lane() == 0 ? -14 : 14), y = top - p.y();
                    w.circle(x, y, p.radius(), ReportEmotionData.color(p.record().getPrimaryEmotionCode()), p.record().getPrimaryIntensity() == null);
                    String number = Integer.toString(p.number());
                    float size = Math.min(6, p.radius() * 1.6f / (w.font.getStringWidth(number) / 1000));
                    float numberWidth = w.font.getStringWidth(number) / 1000 * size;
                    w.writeCellText(number, x - numberWidth / 2, y - size / 3, size);
                }
                w.cursorY = top - 490;
                w.writeParagraph("동일 시각에 기록이 밀집되면 같은 주의 추가 그래프에 나누어 표시합니다. 시각 위치는 변경하지 않습니다.", 9);
            }
        }
        w.addPage();
        w.writeSectionTitle("요일별 기록 수 · 선택 범위 전체");
        List<List<EmotionRecords>> byDay = new ArrayList<>(), byTime = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            int day = d;
            byDay.add(records.stream().filter(r -> r.getOccurredAt().atZone(zone).getDayOfWeek().getValue() - 1 == day).toList());
        }
        for (int b = 0; b < 5; b++) {
            int bucket = b;
            byTime.add(records.stream().filter(r -> ReportEmotionData.bucket(r.getOccurredAt().atZone(zone).getHour()) == bucket).toList());
        }
        long dayMax = byDay.stream().mapToLong(List::size).max().orElse(1);
        for (int d = 0; d < 7; d++) stacked(w, DAYS[d] + "요일", byDay.get(d).size(), ReportEmotionData.counts(byDay.get(d)), dayMax, emotions);
        w.ensureSpace(130);
        w.writeSectionTitle("시간대별 기록 수 · 선택 범위 전체");
        w.writeParagraph("구간은 시작 시각 이상, 끝 시각 미만입니다. 막대 길이는 실제 기록 건수에 비례합니다.", 9);
        long timeMax = byTime.stream().mapToLong(List::size).max().orElse(1);
        for (int b = 0; b < 5; b++) stacked(w, BUCKETS[b], byTime.get(b).size(), ReportEmotionData.counts(byTime.get(b)), timeMax, emotions);
    }
}
