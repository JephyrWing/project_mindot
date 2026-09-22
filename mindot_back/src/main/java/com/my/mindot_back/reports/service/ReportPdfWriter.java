package com.my.mindot_back.reports.service;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
final class ReportPdfWriter implements AutoCloseable {
    void ensureSpace(float height) throws IOException {
        if (cursorY - height < PAGE_MARGIN) addPage();
    }

    void rectangle(float x, float y, float width, float height, java.awt.Color color) throws IOException {
        contentStream.setNonStrokingColor(color);
        contentStream.addRect(x, y, width, height);
        contentStream.fill();
        contentStream.setNonStrokingColor(java.awt.Color.BLACK);
    }

    void line(float x1, float y1, float x2, float y2) throws IOException {
        contentStream.setStrokingColor(java.awt.Color.LIGHT_GRAY);
        contentStream.setLineWidth(0.4f);
        contentStream.moveTo(x1, y1); contentStream.lineTo(x2, y2); contentStream.stroke();
    }

    void circle(float x, float y, float radius, java.awt.Color color, boolean hollow) throws IOException {
        float c = radius * 0.55228475f;
        contentStream.setStrokingColor(color);
        contentStream.setNonStrokingColor(hollow ? java.awt.Color.WHITE : color);
        contentStream.setLineWidth(1.2f);
        contentStream.moveTo(x + radius, y);
        contentStream.curveTo(x + radius, y + c, x + c, y + radius, x, y + radius);
        contentStream.curveTo(x - c, y + radius, x - radius, y + c, x - radius, y);
        contentStream.curveTo(x - radius, y - c, x - c, y - radius, x, y - radius);
        contentStream.curveTo(x + c, y - radius, x + radius, y - c, x + radius, y);
        contentStream.closePath(); contentStream.fillAndStroke();
    }

    static final float PAGE_MARGIN = 60f;
    static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    static final float INFO_LABEL_WIDTH = 110f;
    static final float INFO_ROW_HEIGHT = 25f;

    final PDDocument document;
    final PDType0Font font;
    PDPageContentStream contentStream;
    float cursorY;

    ReportPdfWriter(
            PDDocument document,
            PDType0Font font
    ) throws IOException {
        this.document = document;
        this.font = font;
        addPage();
    }

    // 새 A4 페이지를 만들고 이전 페이지 작성 스트림을 닫음
    void addPage() throws IOException {
        if (contentStream != null) {
            writePageNumber();
            contentStream.close();
        }

        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        contentStream = new PDPageContentStream(document, page);
        cursorY = PAGE_HEIGHT - PAGE_MARGIN;
    }

    // 페이지 부족시 다음 페이지 만든 뒤 작성
    void writeLine(
            String text,
            float fontSize
    ) throws IOException {
        if (cursorY - fontSize < PAGE_MARGIN) {
            addPage();
        }

        contentStream.beginText();
        contentStream.setFont(font, fontSize);
        contentStream.newLineAtOffset(PAGE_MARGIN, cursorY);
        contentStream.showText(safeText(text));
        contentStream.endText();

        cursorY -= fontSize + 9f;
    }

    // 문서 첫 제목을 페이지 가운데에 크게 작성
    void writeCenteredTitle(
            String text,
            float fontSize
    ) throws IOException {
        if (cursorY - fontSize < PAGE_MARGIN) {
            addPage();
        }

        float textWidth = font.getStringWidth(safeText(text)) / 1000 * fontSize;
        float startX = (PAGE_WIDTH - textWidth) / 2;

        contentStream.beginText();
        contentStream.setNonStrokingColor(
                20f / 255f,
                54f / 255f,
                104f / 255f
        );
        contentStream.setFont(font, fontSize);
        contentStream.newLineAtOffset(startX, cursorY);
        contentStream.showText(safeText(text));
        contentStream.endText();

        contentStream.setNonStrokingColor(0, 0, 0);
        cursorY -= fontSize + 15f;
    }

    // 파란 막대와 함께 본문 섹션 제목을 작성
    void writeSectionTitle(String text) throws IOException {
        float barHeight = 18f;

        if (cursorY - barHeight - 40 < PAGE_MARGIN) {
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
        contentStream.showText(safeText(text));
        contentStream.endText();

        contentStream.setNonStrokingColor(0, 0, 0);
        cursorY -= 27f;
    }

    // CBT 결과의 제목을 파란색으로 강조해 작성
    void writeAccentLine(
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
        contentStream.showText(safeText(text));
        contentStream.endText();

        contentStream.setNonStrokingColor(0, 0, 0);
        cursorY -= fontSize + 9f;
    }

    void writeConversationBlock(String speaker, String text) throws IOException {
        writePagedRow(speaker, text, 82f, 9f);
    }

    // 텍스트 없이 세로 여백만 추가
    void addSpace(float space) throws IOException {
        if (cursorY - space < PAGE_MARGIN) {
            addPage();
        }

        cursorY -= space;
    }

    void writeInfoRow(String label, String value) throws IOException {
        writePagedRow(label, value, INFO_LABEL_WIDTH, 10f);
    }

    // Both conversation and information rows split by available lines on every page.
    void writePagedRow(String label, String value, float labelWidth, float size) throws IOException {
        float tableWidth = PAGE_WIDTH - PAGE_MARGIN * 2;
        List<String> lines = wrapText(value, size, tableWidth - labelWidth - 14f);
        int offset = 0;
        do {
            String pageLabel = label + (offset > 0 ? " (이어짐)" : "");
            List<String> labels = wrapText(pageLabel, size, labelWidth - 14f);
            int capacity = (int)Math.floor((cursorY - PAGE_MARGIN - 10f) / 14f);
            if (capacity < Math.max(1, labels.size())) {
                addPage();
                capacity = (int)Math.floor((cursorY - PAGE_MARGIN - 10f) / 14f);
            }
            int count = Math.min(capacity, lines.size() - offset);
            float height = Math.max(labels.size(), count) * 14f + 10f;
            float bottom = cursorY - height;
            contentStream.setNonStrokingColor(241f/255, 245f/255, 249f/255);
            contentStream.addRect(PAGE_MARGIN, bottom, labelWidth, height);
            contentStream.fill();
            contentStream.setStrokingColor(196f/255, 207f/255, 222f/255);
            contentStream.addRect(PAGE_MARGIN, bottom, tableWidth, height);
            contentStream.moveTo(PAGE_MARGIN + labelWidth, bottom);
            contentStream.lineTo(PAGE_MARGIN + labelWidth, cursorY);
            contentStream.stroke();
            for (int i = 0; i < labels.size(); i++)
                writeCellText(labels.get(i), PAGE_MARGIN + 7f, cursorY - 15f - i*14f, size);
            for (int i = 0; i < count; i++)
                writeCellText(lines.get(offset+i), PAGE_MARGIN + labelWidth + 7f, cursorY - 15f - i*14f, size);
            cursorY = bottom;
            offset += count;
            if (offset < lines.size()) addPage();
        } while (offset < lines.size());
    }

    void writeCellText(String text, float x, float y, float size) throws IOException {
        contentStream.beginText();
        contentStream.setNonStrokingColor(0, 0, 0);
        contentStream.setFont(font, size);
        contentStream.newLineAtOffset(x, y);
        contentStream.showText(safeText(text));
        contentStream.endText();
    }

    // No missing glyph is silently discarded. Mark the exact Unicode code point.
    String safeText(String text) throws IOException {
        if (text == null) return "";
        StringBuilder result = new StringBuilder();
        for (int cp : text.codePoints().toArray()) {
            String glyph = new String(Character.toChars(cp));
            try { font.getStringWidth(glyph); result.append(glyph); }
            catch (IllegalArgumentException unsupported) { result.append(String.format("[U+%04X]", cp)); }
        }
        return result.toString();
    }

    // PDF 가로 폭에 맞춰 긴 문장을 여러 줄로 분리
    List<String> wrapText(
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
    List<String> wrapText(
            String text,
            float fontSize,
            float maxWidth
    ) throws IOException {
        List<String> lines = new ArrayList<>();
        String normalized = (text == null ? "" : text).replace("\r\n", "\n").replace('\r', '\n');
        for (String paragraph : normalized.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (int cp : safeText(paragraph).codePoints().toArray()) {
                String glyph = new String(Character.toChars(cp));
                String candidate = line.toString() + glyph;
                if (line.length() > 0 && font.getStringWidth(candidate) / 1000 * fontSize > maxWidth) {
                    lines.add(line.toString()); line.setLength(0);
                }
                line.append(glyph);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    // 긴 문장을 줄바꿈한 뒤 페이지 단위로 작성
    void writeParagraph(
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
            writePageNumber();
            contentStream.close();
            contentStream = null;
        }
    }

    private void writePageNumber() throws IOException {
        writeCellText("MINDOT · " + document.getNumberOfPages(), PAGE_MARGIN, 30, 8);
    }
}
