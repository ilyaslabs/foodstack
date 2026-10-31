package io.github.ilyaslabs.foodstack.media.core.processing;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;

/**
 * Turns an uploaded image into the fixed WebP variants (TDD 13): checks magic bytes and limits,
 * applies the EXIF orientation, drops all metadata (re-encoding keeps none), resizes without
 * upscaling and encodes WebP at quality 0.8.
 * <p>
 * Pure Java plus the native libwebp bundled in {@code webp-imageio}, so the server needs no
 * system image libraries.
 */
public class ImageProcessor {

    private static final long MAX_PIXELS = 50_000_000L;

    private static final float WEBP_QUALITY = 0.8f;

    private final long maxBytes;

    public ImageProcessor(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** The detected format of an upload: {@code jpeg}, {@code png} or {@code webp}. */
    public static String detectFormat(byte[] content) {
        if (content.length >= 3 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8 && (content[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        if (content.length >= 8 && (content[0] & 0xFF) == 0x89 && content[1] == 'P' && content[2] == 'N' && content[3] == 'G') {
            return "png";
        }
        if (content.length >= 12 && content[0] == 'R' && content[1] == 'I' && content[2] == 'F' && content[3] == 'F'
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
            return "webp";
        }
        return null;
    }

    public List<ProcessedImage> process(byte[] content) {
        if (content.length > this.maxBytes) {
            throw new ImageRejectedException("MEDIA_TOO_LARGE", "The image is larger than " + this.maxBytes + " bytes");
        }
        String format = detectFormat(content);
        if (format == null) {
            throw new ImageRejectedException("MEDIA_UNSUPPORTED_TYPE", "Only JPEG, PNG and WebP images are accepted");
        }
        BufferedImage image = orient(decode(content, format), orientation(content));
        List<ProcessedImage> variants = new ArrayList<>();
        for (ImageVariant variant : ImageVariant.values()) {
            BufferedImage resized = resize(image, variant.maxSide());
            variants.add(new ProcessedImage(variant, resized.getWidth(), resized.getHeight(), encodeWebp(resized)));
        }
        return variants;
    }

    private static BufferedImage decode(byte[] content, String format) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            if (!readers.hasNext()) {
                throw new ImageRejectedException("MEDIA_UNSUPPORTED_TYPE", "No decoder for " + format);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw new ImageRejectedException("MEDIA_TOO_LARGE", "The image has too many pixels");
                }
                return reader.read(0);
            }
            finally {
                reader.dispose();
            }
        }
        catch (IOException | RuntimeException ex) {
            if (ex instanceof ImageRejectedException rejected) {
                throw rejected;
            }
            throw new ImageRejectedException("MEDIA_CORRUPT", "The image could not be read");
        }
    }

    private static int orientation(byte[] content) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(content));
            ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        }
        catch (ImageProcessingException | IOException | MetadataException ex) {
            // No readable EXIF: treat as upright.
        }
        return 1;
    }

    /** Applies an EXIF orientation (1 to 8) so the pixels are upright. */
    static BufferedImage orient(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform transform = new AffineTransform();
        switch (orientation) {
            case 2 -> { transform.scale(-1, 1); transform.translate(-w, 0); }
            case 3 -> { transform.translate(w, h); transform.rotate(Math.PI); }
            case 4 -> { transform.scale(1, -1); transform.translate(0, -h); }
            case 5 -> { transform.rotate(-Math.PI / 2); transform.scale(-1, 1); }
            case 6 -> { transform.translate(h, 0); transform.rotate(Math.PI / 2); }
            case 7 -> { transform.scale(-1, 1); transform.translate(-h, 0); transform.translate(0, w); transform.rotate(3 * Math.PI / 2); }
            case 8 -> { transform.translate(0, w); transform.rotate(3 * Math.PI / 2); }
            default -> { }
        }
        BufferedImage target = new BufferedImage(swap ? h : w, swap ? w : h, rgbType(image));
        Graphics2D g = target.createGraphics();
        try {
            g.drawImage(image, transform, null);
        }
        finally {
            g.dispose();
        }
        return target;
    }

    /** Scales so the longest side is at most {@code maxSide}, in halving steps for quality. */
    static BufferedImage resize(BufferedImage image, int maxSide) {
        int w = image.getWidth();
        int h = image.getHeight();
        double scale = Math.min(1.0, (double) maxSide / Math.max(w, h));
        int targetW = Math.max(1, (int) Math.round(w * scale));
        int targetH = Math.max(1, (int) Math.round(h * scale));
        BufferedImage current = toRgb(image);
        while (current.getWidth() / 2 >= targetW && current.getHeight() / 2 >= targetH) {
            current = draw(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return (current.getWidth() == targetW && current.getHeight() == targetH) ? current : draw(current, targetW, targetH);
    }

    private static BufferedImage draw(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, rgbType(source));
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, width, height, null);
        }
        finally {
            g.dispose();
        }
        return target;
    }

    private static BufferedImage toRgb(BufferedImage image) {
        int type = rgbType(image);
        if (image.getType() == type) {
            return image;
        }
        return draw(image, image.getWidth(), image.getHeight());
    }

    private static int rgbType(BufferedImage image) {
        return image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    }

    private static byte[] encodeWebp(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/webp");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No WebP encoder available");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionType(param.getCompressionTypes()[0]);
            param.setCompressionQuality(WEBP_QUALITY);
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Could not encode WebP", ex);
        }
        finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
