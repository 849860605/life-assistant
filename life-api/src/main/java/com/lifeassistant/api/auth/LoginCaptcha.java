package com.lifeassistant.api.auth;

import com.lifeassistant.core.config.LifeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.CubicCurve2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * 登录用的图形验证码：4 位字母数字（去掉 0O1IL 这些容易看错的），PNG 图片，字符随机旋转、加干扰线和噪点。
 * 答案存 Redis 5 分钟，一张图只能核对一次（对错都作废，错了换一张）。只在输错几次后才要（见 LoginGuard）。
 * 字体用 PDF 那套思源黑体（life.pdf.font-dir），找不到时用 Java 自带的无衬线字体。
 */
@Slf4j
@Component
public class LoginCaptcha {

    static final Duration TTL = Duration.ofMinutes(5);
    private static final String PREFIX = "captcha:";
    private static final String CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int LENGTH = 4;
    private static final int WIDTH = 120;
    private static final int HEIGHT = 44;

    private final StringRedisTemplate redis;
    private final Font font;
    private final SecureRandom random = new SecureRandom();

    public LoginCaptcha(StringRedisTemplate redis, LifeProperties properties) {
        this.redis = redis;
        this.font = loadFont(properties.getPdf().getFontDir());
    }

    /** 一张验证码：id 提交时带回来，image 是 data:image/png;base64,… 直接放进 img。 */
    public record Captcha(String id, String image) {
    }

    /** 出一张新的。 */
    public Captcha issue() {
        StringBuilder answer = new StringBuilder();
        for (int i = 0; i < LENGTH; i++) {
            answer.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(PREFIX + id, answer.toString(), TTL);
        return new Captcha(id, "data:image/png;base64," + Base64.getEncoder().encodeToString(render(answer.toString())));
    }

    /** 核对：不区分大小写、忽略空格；不管对错这张都作废。 */
    public boolean verify(String id, String code) {
        if (id == null || code == null || id.length() > 64) {
            return false;
        }
        String answer;
        try {
            answer = redis.opsForValue().getAndDelete(PREFIX + id);
        } catch (RuntimeException e) {
            log.warn("核对验证码失败：{}", e.getMessage());
            return false;
        }
        return answer != null && answer.equals(code.replaceAll("\\s", "").toUpperCase(Locale.ROOT));
    }

    /** 画图：浅色底、深色字，每个字随机旋转和上下错开，再加两条贝塞尔干扰线和一些噪点。 */
    byte[] render(String text) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(240 + random.nextInt(12), 244 + random.nextInt(8), 250));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            for (int i = 0; i < 40; i++) {
                g.setColor(tint(150, 210));
                g.fillOval(random.nextInt(WIDTH), random.nextInt(HEIGHT), 2, 2);
            }
            int step = (WIDTH - 16) / LENGTH;
            for (int i = 0; i < text.length(); i++) {
                Font f = font.deriveFont(Font.BOLD, 26f + random.nextInt(5));
                AffineTransform at = new AffineTransform();
                at.rotate(Math.toRadians(random.nextInt(51) - 25));
                g.setFont(f.deriveFont(at));
                g.setColor(tint(20, 110));
                g.drawString(String.valueOf(text.charAt(i)), 10 + i * step + random.nextInt(6), 31 + random.nextInt(7) - 3);
            }
            g.setStroke(new BasicStroke(1.6f));
            for (int i = 0; i < 2; i++) {
                g.setColor(tint(60, 150));
                g.draw(new CubicCurve2D.Double(0, random.nextInt(HEIGHT), WIDTH / 3.0, random.nextInt(HEIGHT),
                        WIDTH * 2 / 3.0, random.nextInt(HEIGHT), WIDTH, random.nextInt(HEIGHT)));
            }
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Color tint(int min, int max) {
        return new Color(min + random.nextInt(max - min), min + random.nextInt(max - min), min + random.nextInt(max - min));
    }

    /** 思源黑体粗体；相对路径找不到时再到上一级找（本地 mvn -pl life-api 启动时当前目录是 life-api）。 */
    private static Font loadFont(String dir) {
        File configured = new File(dir, "NotoSansSC-Bold.ttf");
        File parent = new File(new File("..", dir), "NotoSansSC-Bold.ttf");
        File file = configured.isFile() ? configured : parent;
        if (file.isFile()) {
            try {
                return Font.createFont(Font.TRUETYPE_FONT, file);
            } catch (Exception e) {
                log.warn("验证码字体加载失败，用默认字体：{}", e.getMessage());
            }
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, 26);
    }
}
