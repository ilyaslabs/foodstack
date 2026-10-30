package io.github.ilyaslabs.foodstack.media.core.processing;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import javax.imageio.ImageIO;

/** Generates real images for tests. */
public final class TestImages {

    private TestImages() {
    }

    /** A two-colour image: left half red, right half blue. */
    public static byte[] image(String format, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width / 2, height);
        g.setColor(Color.BLUE);
        g.fillRect(width / 2, 0, width - width / 2, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }
}
