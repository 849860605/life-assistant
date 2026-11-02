package com.lifeassistant.core.file;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 把上传的文件转成文字：PDF 先抽文字，抽不出来（扫描件）就把前几页转成图片交给看图模型；
 * Word 抽正文和表格，Excel 按工作表逐行抽，图片直接交给看图模型。
 */
@Component
public class FileTextReader {

    /** 扫描件最多识别几页，太多既慢又贵 */
    static final int MAX_SCAN_PAGES = 4;
    /** 送给看图模型前把图缩到长边不超过这么多像素 */
    static final int MAX_SIDE = 2000;
    /** Excel 每个工作表最多读多少行 */
    static final int MAX_ROWS = 400;

    private final VisionReader vision;

    public FileTextReader(VisionReader vision) {
        this.vision = vision;
    }

    /** 识别结果：文字和页数（PDF 页数、Excel 工作表数，其他为空）。 */
    public record Result(String text, Integer pages) {
    }

    /** 按类型识别；看图失败会抛 BizException，文件损坏抛 IOException。 */
    public Result read(UploadKind kind, byte[] bytes, String mime, Long userId) throws IOException {
        return switch (kind) {
            case IMAGE -> new Result(vision.read(List.of(shrink(bytes, mime)), userId), null);
            case PDF -> pdf(bytes, userId);
            case WORD -> new Result(word(bytes), null);
            case EXCEL -> excel(bytes);
            case TEXT -> new Result(new String(bytes, StandardCharsets.UTF_8), null);
        };
    }

    /** PDF：每页平均不到 20 个字就当扫描件，前几页转图片识别。 */
    private Result pdf(byte[] bytes, Long userId) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            int pages = doc.getNumberOfPages();
            String text = new PDFTextStripper().getText(doc).strip();
            if (text.replaceAll("\\s", "").length() >= 20 * pages) {
                return new Result(text, pages);
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            List<VisionReader.Image> images = new ArrayList<>();
            for (int i = 0; i < Math.min(pages, MAX_SCAN_PAGES); i++) {
                images.add(jpeg(fit(renderer.renderImageWithDPI(i, 150, ImageType.RGB))));
            }
            String read = vision.read(images, userId);
            if (pages > MAX_SCAN_PAGES) {
                read += "\n（扫描件共 " + pages + " 页，只识别了前 " + MAX_SCAN_PAGES + " 页）";
            }
            return new Result(read, pages);
        }
    }

    /** Word（docx）：正文段落和表格。 */
    private static String word(byte[] bytes) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText().strip();
        }
    }

    /** Excel（xlsx / xls）：每个工作表一段，每行单元格用 | 分隔，空行跳过。 */
    private static Result excel(byte[] bytes) throws IOException {
        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter format = new DataFormatter();
            StringBuilder out = new StringBuilder();
            for (Sheet sheet : book) {
                out.append("## 工作表：").append(sheet.getSheetName()).append('\n');
                int rows = 0;
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        cells.add(format.formatCellValue(cell).strip());
                    }
                    if (cells.stream().allMatch(String::isEmpty)) {
                        continue;
                    }
                    if (++rows > MAX_ROWS) {
                        out.append("（后面还有行，没读）\n");
                        break;
                    }
                    out.append(String.join(" | ", cells)).append('\n');
                }
                out.append('\n');
            }
            return new Result(out.toString().strip(), book.getNumberOfSheets());
        }
    }

    /** 图片缩到合适大小再转 JPEG；读不了的格式（如 webp）原样送。 */
    private static VisionReader.Image shrink(byte[] bytes, String mime) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            return new VisionReader.Image(bytes, mime);
        }
        if (Math.max(image.getWidth(), image.getHeight()) <= MAX_SIDE && bytes.length < 3_000_000
                && ("image/jpeg".equals(mime) || "image/png".equals(mime))) {
            return new VisionReader.Image(bytes, mime);
        }
        return jpeg(fit(image));
    }

    /** 长边超过 MAX_SIDE 时等比缩小，去掉透明通道。 */
    private static BufferedImage fit(BufferedImage image) {
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(image.getWidth(), image.getHeight()));
        int w = (int) Math.round(image.getWidth() * scale);
        int h = (int) Math.round(image.getHeight() * scale);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(image, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static VisionReader.Image jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return new VisionReader.Image(out.toByteArray(), "image/jpeg");
    }
}
