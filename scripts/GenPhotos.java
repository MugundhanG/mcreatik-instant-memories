import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Generates camera-like JPEGs for simulations: java scripts/GenPhotos.java <outDir> <count> [width] [height]
 * Each image is unique (different checksum) and labelled so it can be recognised in the gallery.
 */
public class GenPhotos {
    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        int count = Integer.parseInt(args[1]);
        int w = args.length > 2 ? Integer.parseInt(args[2]) : 3000;
        int h = args.length > 3 ? Integer.parseInt(args[3]) : 2000;
        out.mkdirs();
        Random r = new Random();
        for (int i = 1; i <= count; i++) {
            boolean portrait = i % 4 == 0;
            int iw = portrait ? h : w, ih = portrait ? w : h;
            BufferedImage img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float hue = r.nextFloat();
            g.setPaint(new GradientPaint(0, 0, Color.getHSBColor(hue, 0.35f, 0.95f), iw, ih, Color.getHSBColor((hue + 0.15f) % 1, 0.55f, 0.45f)));
            g.fillRect(0, 0, iw, ih);
            for (int k = 0; k < 12; k++) {
                g.setColor(new Color(255, 255, 255, 20 + r.nextInt(40)));
                int s = 100 + r.nextInt(Math.min(iw, ih) / 2);
                g.fillOval(r.nextInt(iw), r.nextInt(ih), s, s);
            }
            g.setColor(new Color(255, 255, 255, 230));
            g.setFont(new Font(Font.SERIF, Font.PLAIN, Math.min(iw, ih) / 9));
            String label = "#" + i;
            FontMetrics fm = g.getFontMetrics();
            g.drawString(label, (iw - fm.stringWidth(label)) / 2, ih / 2 + fm.getAscent() / 3);
            g.dispose();
            ImageIO.write(img, "jpg", new File(out, String.format("SIM_%04d.JPG", i)));
        }
        System.out.println("Generated " + count + " photos in " + out);
    }
}
