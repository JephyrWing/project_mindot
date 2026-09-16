// 저장된 월간 리포트를 시각적인 2페이지 PDF로 변환하는 Service
package com.my.mindot_back.reports.service;

import com.my.mindot_back.reports.dto.MonthlyReportResponseDto;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import com.my.mindot_back.reports.dto.MonthlyReportDailyTrendDto;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import java.awt.Color;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import java.io.InputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.YearMonth;

@Service
@RequiredArgsConstructor
public class MonthlyReportPdfService {

    // PDF 전체에서 공통으로 사용하는 Mindot 색상
    private static final Color PRIMARY_BLUE =
            new Color(35, 100, 216);
    private static final Color DARK_TEXT =
            new Color(20, 33, 61);
    private static final Color MUTED_TEXT =
            new Color(104, 115, 138);
    private static final Color LIGHT_BLUE =
            new Color(234, 241, 255);
    private static final Color DIVIDER =
            new Color(220, 227, 240);
    private static final Color ACCENT_GREEN =
            new Color(44, 139, 115);
    private static final Color BAR_TRACK =
            new Color(238, 242, 248);

    // 월간 리포트의 날짜 표시 형식
    private static final DateTimeFormatter REPORT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일");

    // 주차별 표에서 사용할 짧은 날짜 형식
    private static final DateTimeFormatter SHORT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("M.d");

    // 감정 코드를 사용자용 한국어로 변환
    private static final Map<String, String> EMOTION_LABELS =
            Map.ofEntries(
                    Map.entry("ANXIETY", "불안"),
                    Map.entry("FEAR", "두려움"),
                    Map.entry("ANGER", "분노"),
                    Map.entry("FRUSTRATION", "답답함"),
                    Map.entry("SADNESS", "슬픔"),
                    Map.entry("DISAPPOINTMENT", "실망"),
                    Map.entry("SHAME", "수치심"),
                    Map.entry("GUILT", "죄책감"),
                    Map.entry("LONELINESS", "외로움"),
                    Map.entry("JOY", "기쁨"),
                    Map.entry("RELIEF", "안도"),
                    Map.entry("ACHIEVEMENT", "성취감"),
                    Map.entry("CALM", "평온"),
                    Map.entry("GRATITUDE", "감사"),
                    Map.entry("EXCITEMENT", "설렘"),
                    Map.entry("OTHER", "기타")
            );

    // 상황 분류 코드를 사용자용 한국어로 변환
    private static final Map<String, String> CONTEXT_LABELS =
            Map.ofEntries(
                    Map.entry("SOCIAL_EVALUATION", "사회적 평가"),
                    Map.entry("PERFORMANCE", "발표·시험"),
                    Map.entry("PROMISE", "약속"),
                    Map.entry("MISTAKE", "실수"),
                    Map.entry("CONFLICT", "갈등"),
                    Map.entry("REJECTION", "거절·소외"),
                    Map.entry("WORK", "업무"),
                    Map.entry("STUDY", "학업"),
                    Map.entry("HEALTH", "건강"),
                    Map.entry("DAILY_LIFE", "일상"),
                    Map.entry("OTHER", "기타")
            );

    // 저장된 월간 리포트를 조회하는 기존 Service
    private final MonthlyReportsService monthlyReportsService;

    // PDF에 사용자 이름 등을 표시하기 위한 Repository
    private final UsersRepository usersRepository;

    /*
     * 선택한 달의 저장된 월간 리포트를 조회하고 PDF로 변환
     *
     * 1페이지: 핵심 지표, 월간 요약, 감정 강도 그래프, 분포 그래프
     * 2페이지: 주차별 표, CBT 요약, 데이터 해석 안내
     */
    @Transactional(readOnly = true)
    public byte[] exportMonthlyPdf(
            Long userId,
            YearMonth month
    ) {
        Users user = usersRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "사용자를 찾을 수 없습니다."
                ));

        // 이미 생성된 해당 사용자의 월간 리포트만 조회
        MonthlyReportResponseDto report =
                monthlyReportsService.getMonthlyReport(userId, month);

        try (
                PDDocument document = new PDDocument();
                ByteArrayOutputStream outputStream =
                        new ByteArrayOutputStream()
        ) {
            // 기존 상담용 PDF와 같은 한글 폰트를 문서에 포함
            PDType0Font koreanFont = loadKoreanFont(document);

            writeOverviewPage(document, koreanFont, user, report);
            writeDetailPage(document, koreanFont, report);

            document.save(outputStream);
            return outputStream.toByteArray();
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "월간 리포트 PDF 생성에 실패했습니다.",
                    exception
            );
        }
    }

    // 첫 페이지에 월간 리포트 제목과 핵심 지표를 작성
    private void writeOverviewPage(
            PDDocument document,
            PDType0Font font,
            Users user,
            MonthlyReportResponseDto report
    ) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);

        try (PDPageContentStream stream =
                     new PDPageContentStream(document, page)) {

            // Mindot 브랜드와 문서 제목
            writeText(
                    stream,
                    font,
                    "MINDOT",
                    10f,
                    45f,
                    800f,
                    PRIMARY_BLUE
            );

            writeText(
                    stream,
                    font,
                    "월간 마음 리포트",
                    24f,
                    45f,
                    765f,
                    DARK_TEXT
            );

            String periodText =
                    report.periodStart().format(REPORT_DATE_FORMATTER)
                            + " - "
                            + report.periodEnd().format(REPORT_DATE_FORMATTER);

            writeText(
                    stream,
                    font,
                    periodText,
                    10f,
                    45f,
                    742f,
                    MUTED_TEXT
            );

            // 오른쪽 위에 사용자 이름 표시
            writeRightAlignedText(
                    stream,
                    font,
                    user.getDisplayName() + " 님",
                    10f,
                    PDRectangle.A4.getWidth() - 45f,
                    765f,
                    MUTED_TEXT
            );

            // 제목 영역과 본문 영역 구분선
            stream.setStrokingColor(PRIMARY_BLUE);
            stream.setLineWidth(1.5f);
            stream.moveTo(45f, 725f);
            stream.lineTo(PDRectangle.A4.getWidth() - 45f, 725f);
            stream.stroke();

            // A4 본문 폭 안에 핵심 지표 카드 4개 배치
            float cardY = 640f;
            float cardHeight = 62f;
            float cardWidth = 120f;
            float cardGap = 8f;
            float cardStartX = 45f;

            drawMetricCard(
                    stream,
                    font,
                    cardStartX,
                    cardY,
                    cardWidth,
                    cardHeight,
                    "감정 기록",
                    report.recordCount() + "건"
            );

            drawMetricCard(
                    stream,
                    font,
                    cardStartX + cardWidth + cardGap,
                    cardY,
                    cardWidth,
                    cardHeight,
                    "주요 감정",
                    emotionLabel(report.dominantEmotionCode())
            );

            drawMetricCard(
                    stream,
                    font,
                    cardStartX + (cardWidth + cardGap) * 2,
                    cardY,
                    cardWidth,
                    cardHeight,
                    "평균 강도",
                    decimalOrDash(report.averageIntensity())
            );

            drawMetricCard(
                    stream,
                    font,
                    cardStartX + (cardWidth + cardGap) * 3,
                    cardY,
                    cardWidth,
                    cardHeight,
                    "완료 CBT",
                    report.completedCbtCount() + "회"
            );

            // 집계 결과를 문장으로 설명하는 월간 요약 영역
            drawSummaryBox(
                    stream,
                    font,
                    report.summaryText(),
                    45f,
                    548f,
                    504f,
                    72f
            );

            // 날짜별 평균 감정 강도 그래프 제목
            writeText(
                    stream,
                    font,
                    "날짜별 감정 강도 흐름",
                    14f,
                    45f,
                    520f,
                    DARK_TEXT
            );

            // 강도가 입력된 날짜만 실제 값으로 표시
            drawIntensityChart(
                    stream,
                    font,
                    report,
                    45f,
                    330f,
                    504f,
                    170f
            );

            writeText(
                    stream,
                    font,
                    "강도가 입력된 기록만 계산하며, 값이 없는 날짜는 선을 연결하지 않습니다.",
                    8f,
                    45f,
                    313f,
                    MUTED_TEXT
            );

            // 한 달 동안 나타난 감정과 상황을 좌우 막대그래프로 비교
            drawDistributionGroup(
                    stream,
                    font,
                    "감정 분포",
                    report.emotionCounts(),
                    EMOTION_LABELS,
                    45f,
                    280f,
                    235f,
                    PRIMARY_BLUE
            );

            drawDistributionGroup(
                    stream,
                    font,
                    "상황 분포",
                    report.contextCategoryCounts(),
                    CONTEXT_LABELS,
                    314f,
                    280f,
                    235f,
                    ACCENT_GREEN
            );

            // 첫 페이지 번호
            writeRightAlignedText(
                    stream,
                    font,
                    "1 / 2",
                    9f,
                    PDRectangle.A4.getWidth() - 45f,
                    30f,
                    MUTED_TEXT
            );
        }
    }

    // 두 번째 페이지에 주차별 기록 표와 CBT·감정 변화 요약 작성
    private void writeDetailPage(
            PDDocument document,
            PDType0Font font,
            MonthlyReportResponseDto report
    ) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);

        try (PDPageContentStream stream =
                     new PDPageContentStream(document, page)) {

            writeText(
                    stream,
                    font,
                    "MINDOT",
                    10f,
                    45f,
                    800f,
                    PRIMARY_BLUE
            );

            writeText(
                    stream,
                    font,
                    "주차별 변화와 CBT 요약",
                    22f,
                    45f,
                    765f,
                    DARK_TEXT
            );

            writeText(
                    stream,
                    font,
                    report.periodStart().getYear()
                            + "년 "
                            + report.periodStart().getMonthValue()
                            + "월",
                    10f,
                    45f,
                    742f,
                    MUTED_TEXT
            );

            stream.setStrokingColor(PRIMARY_BLUE);
            stream.setLineWidth(1.5f);
            stream.moveTo(45f, 725f);
            stream.lineTo(
                    PDRectangle.A4.getWidth() - 45f,
                    725f
            );
            stream.stroke();

            writeText(
                    stream,
                    font,
                    "주차별 감정 기록",
                    14f,
                    45f,
                    695f,
                    DARK_TEXT
            );

            drawWeeklySummaryTable(
                    stream,
                    font,
                    report,
                    45f,
                    675f,
                    504f
            );

            writeText(
                    stream,
                    font,
                    "CBT와 감정 변화",
                    14f,
                    45f,
                    430f,
                    DARK_TEXT
            );

            float metricY = 350f;
            float metricWidth = 120f;
            float metricHeight = 62f;
            float metricGap = 8f;

            drawMetricCard(
                    stream,
                    font,
                    45f,
                    metricY,
                    metricWidth,
                    metricHeight,
                    "완료 CBT",
                    report.completedCbtCount() + "회"
            );

            drawMetricCard(
                    stream,
                    font,
                    45f + metricWidth + metricGap,
                    metricY,
                    metricWidth,
                    metricHeight,
                    "평균 도움 점수",
                    report.averageHelpfulnessScore() == null
                            ? "-"
                            : decimalOrDash(
                                    report.averageHelpfulnessScore()
                            ) + " / 5"
            );

            drawMetricCard(
                    stream,
                    font,
                    45f + (metricWidth + metricGap) * 2,
                    metricY,
                    metricWidth,
                    metricHeight,
                    "월 전반 강도",
                    decimalOrDash(
                            report.firstHalfAverageIntensity()
                    )
            );

            drawMetricCard(
                    stream,
                    font,
                    45f + (metricWidth + metricGap) * 3,
                    metricY,
                    metricWidth,
                    metricHeight,
                    "월 후반 강도",
                    decimalOrDash(
                            report.secondHalfAverageIntensity()
                    )
            );

            drawInformationBox(
                    stream,
                    font,
                    "감정 강도 변화",
                    intensityTrendLabel(report),
                    45f,
                    270f,
                    504f,
                    58f,
                    LIGHT_BLUE
            );

            drawInformationBox(
                    stream,
                    font,
                    "리포트 읽는 방법",
                    "이 리포트는 한 달의 감정 흐름과 기록 습관을 요약합니다. "
                            + "개별 상황, 자동적 사고, CBT 대화 원문은 포함하지 않습니다. "
                            + "상세 내용이 필요하면 주간 리포트 PDF를 이용해 주세요.",
                    45f,
                    155f,
                    504f,
                    92f,
                    new Color(245, 247, 251)
            );

            drawInformationBox(
                    stream,
                    font,
                    "데이터 안내",
                    "평균 강도는 강도가 입력된 기록만 계산합니다. "
                            + "자료가 부족한 기간은 대시 또는 데이터 부족으로 표시하며, "
                            + "본 리포트는 의료적 진단을 대신하지 않습니다.",
                    45f,
                    55f,
                    504f,
                    78f,
                    new Color(245, 247, 251)
            );

            writeRightAlignedText(
                    stream,
                    font,
                    "2 / 2",
                    9f,
                    PDRectangle.A4.getWidth() - 45f,
                    30f,
                    MUTED_TEXT
            );
        }
    }

    // 월간 날짜별 집계를 1~7일, 8~14일 방식의 주차 표로 표시
    private void drawWeeklySummaryTable(
            PDPageContentStream stream,
            PDType0Font font,
            MonthlyReportResponseDto report,
            float x,
            float topY,
            float width
    ) throws IOException {
        List<MonthlyWeekSummary> summaries =
                createWeeklySummaries(report);

        float headerHeight = 30f;
        float rowHeight = 34f;

        // 표 머리글 배경
        stream.setNonStrokingColor(LIGHT_BLUE);
        stream.addRect(
                x,
                topY - headerHeight,
                width,
                headerHeight
        );
        stream.fill();

        float weekCenter = x + 36f;
        float periodCenter = x + 116f;
        float recordCenter = x + 224f;
        float dayCenter = x + 313f;
        float intensityCenter = x + 424f;

        writeCenteredText(
                stream,
                font,
                "주차",
                9f,
                weekCenter,
                topY - 19f,
                MUTED_TEXT
        );
        writeCenteredText(
                stream,
                font,
                "기간",
                9f,
                periodCenter,
                topY - 19f,
                MUTED_TEXT
        );
        writeCenteredText(
                stream,
                font,
                "기록 수",
                9f,
                recordCenter,
                topY - 19f,
                MUTED_TEXT
        );
        writeCenteredText(
                stream,
                font,
                "기록한 날",
                9f,
                dayCenter,
                topY - 19f,
                MUTED_TEXT
        );
        writeCenteredText(
                stream,
                font,
                "강도 입력일 평균",
                9f,
                intensityCenter,
                topY - 19f,
                MUTED_TEXT
        );

        float rowTop = topY - headerHeight;

        for (MonthlyWeekSummary summary : summaries) {
            float textY = rowTop - 21f;

            stream.setStrokingColor(DIVIDER);
            stream.setLineWidth(0.7f);
            stream.moveTo(x, rowTop - rowHeight);
            stream.lineTo(x + width, rowTop - rowHeight);
            stream.stroke();

            writeCenteredText(
                    stream,
                    font,
                    summary.weekNumber() + "주차",
                    9f,
                    weekCenter,
                    textY,
                    DARK_TEXT
            );

            String period =
                    summary.periodStart()
                            .format(SHORT_DATE_FORMATTER)
                            + " - "
                            + summary.periodEnd()
                            .format(SHORT_DATE_FORMATTER);

            writeCenteredText(
                    stream,
                    font,
                    period,
                    9f,
                    periodCenter,
                    textY,
                    DARK_TEXT
            );

            writeCenteredText(
                    stream,
                    font,
                    summary.recordCount() + "건",
                    9f,
                    recordCenter,
                    textY,
                    DARK_TEXT
            );

            writeCenteredText(
                    stream,
                    font,
                    summary.recordedDayCount() + "일",
                    9f,
                    dayCenter,
                    textY,
                    DARK_TEXT
            );

            writeCenteredText(
                    stream,
                    font,
                    decimalOrDash(
                            summary.recordedDayAverageIntensity()
                    ),
                    9f,
                    intensityCenter,
                    textY,
                    PRIMARY_BLUE
            );

            rowTop -= rowHeight;
        }
    }

    // 날짜별 월간 데이터를 최대 5개의 7일 단위 주차로 집계
    private List<MonthlyWeekSummary> createWeeklySummaries(
            MonthlyReportResponseDto report
    ) {
        List<MonthlyReportDailyTrendDto> trends =
                report.dailyTrends() == null
                        ? List.of()
                        : report.dailyTrends();

        List<MonthlyWeekSummary> summaries =
                new ArrayList<>();

        LocalDate weekStart = report.periodStart();
        int weekNumber = 1;

        while (!weekStart.isAfter(report.periodEnd())) {
            LocalDate weekEnd = weekStart
                    .plusDays(6)
                    .isAfter(report.periodEnd())
                    ? report.periodEnd()
                    : weekStart.plusDays(6);

            LocalDate currentWeekStart = weekStart;
            LocalDate currentWeekEnd = weekEnd;

            List<MonthlyReportDailyTrendDto> weekTrends =
                    trends.stream()
                            .filter(trend ->
                                    trend != null
                                            && trend.date() != null
                                            && !trend.date().isBefore(
                                                    currentWeekStart
                                            )
                                            && !trend.date().isAfter(
                                                    currentWeekEnd
                                            )
                            )
                            .toList();

            long recordCount =
                    weekTrends.stream()
                            .mapToLong(
                                    MonthlyReportDailyTrendDto::recordCount
                            )
                            .sum();

            long recordedDayCount =
                    weekTrends.stream()
                            .filter(trend -> trend.recordCount() > 0)
                            .count();

            /*
             * 기록 건수 평균이 아니라 날짜별 평균 강도가 존재하는
             * '기록일 평균'이므로 표 제목도 동일한 의미로 표시
             */
            double intensityAverage =
                    weekTrends.stream()
                            .filter(trend ->
                                    trend.averageIntensity() != null
                            )
                            .mapToDouble(
                                    MonthlyReportDailyTrendDto
                                            ::averageIntensity
                            )
                            .average()
                            .orElse(Double.NaN);

            summaries.add(
                    new MonthlyWeekSummary(
                            weekNumber,
                            weekStart,
                            weekEnd,
                            recordCount,
                            recordedDayCount,
                            Double.isNaN(intensityAverage)
                                    ? null
                                    : intensityAverage
                    )
            );

            weekStart = weekEnd.plusDays(1);
            weekNumber++;
        }

        return summaries;
    }

    // 월 전반과 후반의 강도 변화 상태를 한국어 문장으로 변환
    private String intensityTrendLabel(
            MonthlyReportResponseDto report
    ) {
        if (report.intensityTrend() == null) {
            return "감정 강도 흐름을 확인할 데이터가 부족합니다.";
        }

        return switch (report.intensityTrend()) {
            case INCREASED ->
                    "월 전반보다 후반의 평균 감정 강도가 높아졌습니다.";
            case DECREASED ->
                    "월 전반보다 후반의 평균 감정 강도가 낮아졌습니다.";
            case STABLE ->
                    "월 전반과 후반의 평균 감정 강도가 비슷합니다.";
            case INSUFFICIENT_DATA ->
                    "월 전반과 후반을 비교할 데이터가 부족합니다.";
        };
    }

    // 제목과 설명을 배경 상자 안에 표시
    private void drawInformationBox(
            PDPageContentStream stream,
            PDType0Font font,
            String title,
            String description,
            float x,
            float y,
            float width,
            float height,
            Color backgroundColor
    ) throws IOException {
        stream.setNonStrokingColor(backgroundColor);
        stream.addRect(x, y, width, height);
        stream.fill();

        writeText(
                stream,
                font,
                title,
                10f,
                x + 12f,
                y + height - 20f,
                PRIMARY_BLUE
        );

        writeWrappedText(
                stream,
                font,
                description,
                9f,
                x + 12f,
                y + height - 39f,
                width - 24f,
                13f,
                4,
                DARK_TEXT
        );
    }

    // 코드별 집계값 중 빈도가 높은 항목을 최대 4개까지 가로 막대로 표시
    private void drawDistributionGroup(
            PDPageContentStream stream,
            PDType0Font font,
            String title,
            Map<String, Long> counts,
            Map<String, String> labels,
            float x,
            float startY,
            float width,
            Color barColor
    ) throws IOException {
        writeText(
                stream,
                font,
                title,
                13f,
                x,
                startY,
                DARK_TEXT
        );

        if (counts == null || counts.isEmpty()) {
            writeText(
                    stream,
                    font,
                    "표시할 데이터가 없습니다.",
                    9f,
                    x,
                    startY - 25f,
                    MUTED_TEXT
            );
            return;
        }

        // 빈도가 높은 순서로 최대 4개 항목만 사용
        List<Map.Entry<String, Long>> items =
                counts.entrySet()
                        .stream()
                        .filter(entry ->
                                entry.getValue() != null
                                        && entry.getValue() > 0
                        )
                        .sorted(
                                Map.Entry
                                        .<String, Long>comparingByValue()
                                        .reversed()
                        )
                        .limit(4)
                        .toList();

        if (items.isEmpty()) {
            writeText(
                    stream,
                    font,
                    "표시할 데이터가 없습니다.",
                    9f,
                    x,
                    startY - 25f,
                    MUTED_TEXT
            );
            return;
        }

        long maximumCount =
                items.stream()
                        .mapToLong(Map.Entry::getValue)
                        .max()
                        .orElse(1L);

        float rowY = startY - 25f;
        float barWidth = width;
        float barHeight = 7f;

        for (Map.Entry<String, Long> item : items) {
            String label =
                    labels.getOrDefault(
                            item.getKey(),
                            item.getKey()
                    );

            writeText(
                    stream,
                    font,
                    label,
                    9f,
                    x,
                    rowY,
                    DARK_TEXT
            );

            writeRightAlignedText(
                    stream,
                    font,
                    item.getValue() + "건",
                    9f,
                    x + width,
                    rowY,
                    MUTED_TEXT
            );

            // 모든 항목이 동일한 기준 길이를 갖도록 배경 막대 표시
            stream.setNonStrokingColor(BAR_TRACK);
            stream.addRect(
                    x,
                    rowY - 13f,
                    barWidth,
                    barHeight
            );
            stream.fill();

            float valueWidth =
                    barWidth
                            * item.getValue()
                            / (float) maximumCount;

            // 가장 빈도가 높은 항목을 전체 막대 길이로 표시
            stream.setNonStrokingColor(barColor);
            stream.addRect(
                    x,
                    rowY - 13f,
                    valueWidth,
                    barHeight
            );
            stream.fill();

            rowY -= 37f;
        }
    }

    // 월간 리포트 설명을 옅은 파란 배경 안에 줄바꿈하여 표시
    private void drawSummaryBox(
            PDPageContentStream stream,
            PDType0Font font,
            String summary,
            float x,
            float y,
            float width,
            float height
    ) throws IOException {
        stream.setNonStrokingColor(LIGHT_BLUE);
        stream.addRect(x, y, width, height);
        stream.fill();

        writeText(
                stream,
                font,
                "이번 달 요약",
                10f,
                x + 12f,
                y + height - 19f,
                PRIMARY_BLUE
        );

        writeWrappedText(
                stream,
                font,
                summary == null ? "표시할 월간 요약이 없습니다." : summary,
                9f,
                x + 12f,
                y + height - 38f,
                width - 24f,
                13f,
                3,
                DARK_TEXT
        );
    }

    // 월간 리포트 날짜별 평균 강도를 0~10 범위 선 그래프로 표시
    private void drawIntensityChart(
            PDPageContentStream stream,
            PDType0Font font,
            MonthlyReportResponseDto report,
            float x,
            float y,
            float width,
            float height
    ) throws IOException {
        // 그래프 전체 테두리
        stream.setStrokingColor(DIVIDER);
        stream.setLineWidth(1f);
        stream.addRect(x, y, width, height);
        stream.stroke();

        float plotLeft = x + 38f;
        float plotBottom = y + 25f;
        float plotWidth = width - 53f;
        float plotHeight = height - 42f;

        // 0, 5, 10 기준선과 세로축 숫자 표시
        for (int intensity : List.of(0, 5, 10)) {
            float lineY =
                    plotBottom + (intensity / 10f) * plotHeight;

            stream.setStrokingColor(DIVIDER);
            stream.setLineWidth(0.7f);
            stream.moveTo(plotLeft, lineY);
            stream.lineTo(plotLeft + plotWidth, lineY);
            stream.stroke();

            writeRightAlignedText(
                    stream,
                    font,
                    String.valueOf(intensity),
                    8f,
                    plotLeft - 7f,
                    lineY - 3f,
                    MUTED_TEXT
            );
        }

        LocalDate periodStart = report.periodStart();
        LocalDate periodEnd = report.periodEnd();

        int totalDays =
                (int) ChronoUnit.DAYS.between(
                        periodStart,
                        periodEnd
                ) + 1;

        // 1일, 8일, 15일, 22일 등 일주일 단위로 가로축 날짜 표시
        for (int day = 1; day <= totalDays; day += 7) {
            drawDayTick(
                    stream,
                    font,
                    day,
                    totalDays,
                    plotLeft,
                    plotBottom,
                    plotWidth
            );
        }

        // 마지막 날짜가 앞의 눈금과 다르면 마지막 날도 표시
        if ((totalDays - 1) % 7 != 0) {
            drawDayTick(
                    stream,
                    font,
                    totalDays,
                    totalDays,
                    plotLeft,
                    plotBottom,
                    plotWidth
            );
        }

        List<MonthlyReportDailyTrendDto> trends =
                report.dailyTrends() == null
                        ? List.of()
                        : report.dailyTrends();

        Float previousX = null;
        Float previousY = null;
        LocalDate previousDate = null;
        int displayedPointCount = 0;

        for (MonthlyReportDailyTrendDto trend : trends) {
            if (trend == null
                    || trend.date() == null
                    || trend.averageIntensity() == null) {
                // 중간 날짜의 값이 없으면 다음 점과 선을 연결하지 않음
                previousX = null;
                previousY = null;
                previousDate = null;
                continue;
            }

            long dayOffset =
                    ChronoUnit.DAYS.between(
                            periodStart,
                            trend.date()
                    );

            if (dayOffset < 0 || dayOffset >= totalDays) {
                continue;
            }

            double intensity = Math.max(
                    0d,
                    Math.min(10d, trend.averageIntensity())
            );

            float pointX = totalDays == 1
                    ? plotLeft
                    : plotLeft
                      + (dayOffset / (float) (totalDays - 1))
                        * plotWidth;

            float pointY = plotBottom
                    + ((float) intensity / 10f)
                    * plotHeight;

            // 날짜가 연속된 경우에만 두 점 사이에 선을 그림
            if (previousX != null
                    && previousY != null
                    && previousDate != null
                    && previousDate.plusDays(1).equals(trend.date())) {
                stream.setStrokingColor(PRIMARY_BLUE);
                stream.setLineWidth(2f);
                stream.moveTo(previousX, previousY);
                stream.lineTo(pointX, pointY);
                stream.stroke();
            }

            // PDFBox 기본 도형으로 데이터 점 표시
            stream.setNonStrokingColor(PRIMARY_BLUE);
            stream.addRect(
                    pointX - 2.5f,
                    pointY - 2.5f,
                    5f,
                    5f
            );
            stream.fill();

            previousX = pointX;
            previousY = pointY;
            previousDate = trend.date();
            displayedPointCount++;
        }

        if (displayedPointCount == 0) {
            writeCenteredText(
                    stream,
                    font,
                    "강도가 입력된 기록이 없습니다.",
                    10f,
                    x + width / 2f,
                    y + height / 2f,
                    MUTED_TEXT
            );
        }
    }

    // 그래프의 날짜 눈금 한 개를 표시
    private void drawDayTick(
            PDPageContentStream stream,
            PDType0Font font,
            int day,
            int totalDays,
            float plotLeft,
            float plotBottom,
            float plotWidth
    ) throws IOException {
        float tickX = totalDays == 1
                ? plotLeft
                : plotLeft
                  + ((day - 1) / (float) (totalDays - 1))
                    * plotWidth;

        stream.setStrokingColor(DIVIDER);
        stream.setLineWidth(0.7f);
        stream.moveTo(tickX, plotBottom);
        stream.lineTo(tickX, plotBottom - 4f);
        stream.stroke();

        writeCenteredText(
                stream,
                font,
                day + "일",
                7.5f,
                tickX,
                plotBottom - 14f,
                MUTED_TEXT
        );
    }

    // 가운데 좌표를 기준으로 텍스트를 정렬
    private void writeCenteredText(
            PDPageContentStream stream,
            PDType0Font font,
            String text,
            float fontSize,
            float centerX,
            float y,
            Color color
    ) throws IOException {
        String safeValue = safeText(font, text);
        float textWidth =
                font.getStringWidth(safeValue) / 1000f * fontSize;

        writeText(
                stream,
                font,
                safeValue,
                fontSize,
                centerX - textWidth / 2f,
                y,
                color
        );
    }

    // 지정한 가로 폭에 맞춰 문장을 여러 줄로 작성
    private void writeWrappedText(
            PDPageContentStream stream,
            PDType0Font font,
            String text,
            float fontSize,
            float x,
            float startY,
            float maxWidth,
            float lineHeight,
            int maxLines,
            Color color
    ) throws IOException {
        List<String> lines =
                wrapText(font, text, fontSize, maxWidth);

        int visibleLineCount =
                Math.min(lines.size(), maxLines);

        for (int index = 0;
             index < visibleLineCount;
             index++) {
            String line = lines.get(index);

            // 제한된 줄보다 문장이 길면 마지막 줄에 생략 표시
            if (index == maxLines - 1
                    && lines.size() > maxLines) {
                line = line + "...";
            }

            writeText(
                    stream,
                    font,
                    line,
                    fontSize,
                    x,
                    startY - index * lineHeight,
                    color
            );
        }
    }

    // 한글을 포함한 문장을 실제 글자 폭에 맞춰 여러 줄로 분리
    private List<String> wrapText(
            PDType0Font font,
            String text,
            float fontSize,
            float maxWidth
    ) throws IOException {
        List<String> lines = new ArrayList<>();
        String safeValue = safeText(font, text);
        StringBuilder currentLine = new StringBuilder();

        for (int codePoint : safeValue.codePoints().toArray()) {
            String glyph =
                    new String(Character.toChars(codePoint));
            String candidate =
                    currentLine.toString() + glyph;

            float candidateWidth =
                    font.getStringWidth(candidate)
                            / 1000f
                            * fontSize;

            if (!currentLine.isEmpty()
                    && candidateWidth > maxWidth) {
                lines.add(currentLine.toString());
                currentLine.setLength(0);
            }

            currentLine.append(glyph);
        }

        if (!currentLine.isEmpty()) {
            lines.add(currentLine.toString());
        }

        if (lines.isEmpty()) {
            lines.add("");
        }

        return lines;
    }

    // PDF 좌표에 지정한 크기와 색상으로 한 줄 작성
    private void writeText(
            PDPageContentStream stream,
            PDType0Font font,
            String text,
            float fontSize,
            float x,
            float y,
            Color color
    ) throws IOException {
        stream.beginText();
        stream.setFont(font, fontSize);
        stream.setNonStrokingColor(color);
        stream.newLineAtOffset(x, y);
        stream.showText(safeText(font, text));
        stream.endText();
    }

    // 지정한 오른쪽 좌표를 기준으로 텍스트를 오른쪽 정렬
    private void writeRightAlignedText(
            PDPageContentStream stream,
            PDType0Font font,
            String text,
            float fontSize,
            float rightX,
            float y,
            Color color
    ) throws IOException {
        String safeValue = safeText(font, text);
        float textWidth =
                font.getStringWidth(safeValue) / 1000f * fontSize;

        writeText(
                stream,
                font,
                safeValue,
                fontSize,
                rightX - textWidth,
                y,
                color
        );
    }

    // 핵심 통계 한 건을 옅은 파란 배경의 카드로 표시
    private void drawMetricCard(
            PDPageContentStream stream,
            PDType0Font font,
            float x,
            float y,
            float width,
            float height,
            String label,
            String value
    ) throws IOException {
        stream.setNonStrokingColor(LIGHT_BLUE);
        stream.addRect(x, y, width, height);
        stream.fill();

        writeText(
                stream,
                font,
                label,
                9f,
                x + 10f,
                y + height - 19f,
                MUTED_TEXT
        );

        writeText(
                stream,
                font,
                value,
                16f,
                x + 10f,
                y + 15f,
                DARK_TEXT
        );
    }

    // 감정 코드가 없거나 알 수 없는 경우 안전한 표시값 반환
    private String emotionLabel(String emotionCode) {
        if (emotionCode == null || emotionCode.isBlank()) {
            return "기록 없음";
        }

        return EMOTION_LABELS.getOrDefault(
                emotionCode,
                emotionCode
        );
    }

    // 소수점 통계가 없으면 대시, 있으면 소수점 한 자리로 표시
    private String decimalOrDash(Double value) {
        return value == null
                ? "-"
                : String.format(Locale.ROOT, "%.1f", value);
    }

    // 폰트가 지원하지 않는 문자를 PDFBox 오류 대신 유니코드 코드로 표시
    private String safeText(
            PDType0Font font,
            String text
    ) throws IOException {
        if (text == null) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        for (int codePoint : text.codePoints().toArray()) {
            String glyph =
                    new String(Character.toChars(codePoint));

            try {
                font.getStringWidth(glyph);
                result.append(glyph);
            } catch (IllegalArgumentException exception) {
                result.append(
                        String.format(
                                Locale.ROOT,
                                "[U+%04X]",
                                codePoint
                        )
                );
            }
        }

        return result.toString();
    }

    // resources/fonts에 포함된 나눔고딕을 PDF 문서에 삽입
    private PDType0Font loadKoreanFont(
            PDDocument document
    ) throws IOException {
        ClassPathResource fontResource =
                new ClassPathResource(
                        "fonts/NanumGothic-Regular.ttf"
                );

        try (InputStream inputStream =
                     fontResource.getInputStream()) {
            return PDType0Font.load(
                    document,
                    inputStream,
                    true
            );
        }
    }

    // 월간 날짜별 데이터를 7일 단위로 묶은 PDF 표 한 행
    private record MonthlyWeekSummary(
            int weekNumber,
            LocalDate periodStart,
            LocalDate periodEnd,
            long recordCount,
            long recordedDayCount,
            Double recordedDayAverageIntensity
    ) {
    }
}
