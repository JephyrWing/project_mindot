package com.my.mindot_back.reports.service;

import com.my.mindot_back.users.repository.UsersRepository;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.YearMonth;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class MonthlyReportPdfService {
    private final MonthlyReportsService monthlyReportsService;
    private final UsersRepository usersRepository;

    // Read can upgrade a legacy JSONB snapshot, so this transaction must permit writes.
    @Transactional
    public byte[] exportMonthlyPdf(Long userId, YearMonth month) {
        var user = usersRepository.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        var report = monthlyReportsService.getMonthlyReport(userId, month);
        var c = report.emotionComposition();
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream();
             var fontStream = new ClassPathResource("fonts/NanumGothic-Regular.ttf").getInputStream()) {
            var font = PDType0Font.load(doc, fontStream, true);
            try (var w = new ReportPdfWriter(doc, font)) {
                w.writeCenteredTitle("월간 마음 리포트", 22);
                w.writeInfoRow("이름", user.getDisplayName());
                w.writeInfoRow("집계 기간", report.periodStart() + " - " + report.periodEnd() + " · " + c.timezone());
                w.writeInfoRow("기록 횟수", report.recordCount() + "건");
                w.writeInfoRow("주요 감정", report.dominantEmotionCode() == null ? "입력된 감정 없음" : ReportEmotionData.label(report.dominantEmotionCode()));
                w.writeInfoRow("평균 강도", report.averageIntensity() == null ? "미입력" : String.format(Locale.ROOT, "%.1f/10", report.averageIntensity()));
                w.writeInfoRow("완료 CBT", report.completedCbtCount() + "회");
                w.addSpace(20);
                w.writeSectionTitle("이번 달 마음 흐름");
                w.writeParagraph(report.summaryText(), 10);
                w.writeSectionTitle("기록한 감정");
                ReportPdfCharts.stacked(w, "월 전체 감정 구성", c.recordCount(), c.emotions(), c.recordCount(), c.emotions());
                w.writeSectionTitle("월 초반·후반 비교");
                w.writeParagraph("각 기간의 총기록 수를 분모로 한 100% 감정 구성입니다. 기록이 없는 기간은 채우지 않습니다.", 9);
                for (int i = 0; i < c.halves().size(); i++) {
                    var half = c.halves().get(i);
                    ReportPdfCharts.stacked(w, (i == 0 ? "월 초반 " : "월 후반 ") + half.periodStart() + " - " + half.periodEnd(),
                            half.recordCount(), half.emotions(), half.recordCount(), c.emotions());
                }
                w.addPage();
                ReportPdfCharts.daily(w, c);
                w.writeSectionTitle("기록한 상황");
                w.writeParagraph("막대 길이는 상황별 실제 기록 건수에 비례합니다. 감정별 비율의 분모는 해당 상황의 기록 수입니다.", 9);
                long maximum = c.contexts().stream().mapToLong(g -> g.recordCount()).max().orElse(1);
                for (var group : c.contexts()) ReportPdfCharts.stacked(w, ReportEmotionData.contextLabel(group.value()),
                        group.recordCount(), group.emotions(), maximum, c.emotions());
                if (c.contexts().isEmpty()) w.writeParagraph("기록 없음", 10);
                w.writeSectionTitle("주차별 감정 기록");
                for (int start = 0; start < c.days().size(); start += 7) {
                    var days = c.days().subList(start, Math.min(start + 7, c.days().size()));
                    long total = days.stream().mapToLong(g -> g.recordCount()).sum();
                    long active = days.stream().filter(g -> g.recordCount() > 0).count();
                    w.writeInfoRow(days.get(0).value() + " - " + days.get(days.size() - 1).value(), total + "건 · 기록일 " + active + "일");
                }
                w.addSpace(18);
                w.writeSectionTitle("CBT 요약·데이터 안내");
                w.writeParagraph("완료·확정한 CBT " + report.completedCbtCount() + "회. 감정 통계는 발생일, CBT 통계는 완료일 기준입니다. 평균 강도는 강도가 입력된 기록만 계산합니다.", 10);
                w.writeParagraph("감정 구성은 대표 감정으로 집계하며 보조 감정은 중복 집계하지 않습니다. 상세 기록과 CBT 대화 원문은 주간 화면의 PDF 내보내기에서 선택할 수 있습니다.", 9);
                w.writeParagraph("이 리포트는 자기기록을 정리하며 의료적 진단을 대신하지 않습니다. 최근 집계 시각: "
                        + (report.sourceSnapshotAt() == null ? "집계 시각 정보 없음" : report.sourceSnapshotAt()), 9);
            }
            doc.save(out); return out.toByteArray();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "월간 리포트 PDF 생성에 실패했습니다.", e);
        }
    }
}
