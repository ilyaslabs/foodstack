package io.github.ilyaslabs.foodstack.media.core.processing;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageProcessorTest {

    private final ImageProcessor processor = new ImageProcessor(10L * 1024 * 1024);

    @Test
    @DisplayName("produces thumb, card and detail WebP variants with the longest side limited")
    void variants() throws Exception {
        // when
        List<ProcessedImage> images = this.processor.process(TestImages.image("jpg", 2000, 1000));

        // then
        assertThat(images).extracting(ProcessedImage::variant)
            .containsExactly(ImageVariant.THUMB, ImageVariant.CARD, ImageVariant.DETAIL);
        assertThat(images).extracting(ProcessedImage::width).containsExactly(160, 480, 1080);
        assertThat(images).extracting(ProcessedImage::height).containsExactly(80, 240, 540);
        for (ProcessedImage image : images) {
            assertThat(ImageProcessor.detectFormat(image.webp())).isEqualTo("webp");
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image.webp()));
            assertThat(decoded.getWidth()).isEqualTo(image.width());
        }
    }

    @Test
    @DisplayName("never upscales small images")
    void noUpscaling() {
        List<ProcessedImage> images = this.processor.process(TestImages.image("png", 300, 200));

        assertThat(images).extracting(ProcessedImage::width).containsExactly(160, 300, 300);
    }

    @Test
    @DisplayName("recognises JPEG, PNG and WebP by their magic bytes, whatever the name says")
    void magicBytes() {
        assertThat(ImageProcessor.detectFormat(TestImages.image("jpg", 10, 10))).isEqualTo("jpeg");
        assertThat(ImageProcessor.detectFormat(TestImages.image("png", 10, 10))).isEqualTo("png");
        assertThat(ImageProcessor.detectFormat("<svg></svg>".getBytes(StandardCharsets.UTF_8))).isNull();
    }

    @Test
    @DisplayName("rejects files that are not images")
    void rejectsNonImages() {
        assertThatThrownBy(() -> this.processor.process("#!/bin/sh".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOfSatisfying(ImageRejectedException.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("MEDIA_UNSUPPORTED_TYPE"));
    }

    @Test
    @DisplayName("rejects truncated images")
    void rejectsCorrupt() {
        byte[] jpeg = TestImages.image("jpg", 200, 200);
        byte[] truncated = java.util.Arrays.copyOf(jpeg, 40);

        assertThatThrownBy(() -> this.processor.process(truncated))
            .isInstanceOfSatisfying(ImageRejectedException.class, (ex) -> assertThat(ex.code()).isEqualTo("MEDIA_CORRUPT"));
    }

    @Test
    @DisplayName("rejects uploads over the size limit")
    void rejectsLarge() {
        ImageProcessor small = new ImageProcessor(100);

        assertThatThrownBy(() -> small.process(TestImages.image("png", 200, 200)))
            .isInstanceOfSatisfying(ImageRejectedException.class, (ex) -> assertThat(ex.code()).isEqualTo("MEDIA_TOO_LARGE"));
    }

    @Test
    @DisplayName("turns EXIF-rotated pixels upright")
    void orientation() {
        BufferedImage landscape = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        landscape.setRGB(0, 0, 0xFF0000);

        BufferedImage rotated = ImageProcessor.orient(landscape, 6);
        BufferedImage halfTurn = ImageProcessor.orient(landscape, 3);

        assertThat(rotated.getWidth()).isEqualTo(20);
        assertThat(rotated.getHeight()).isEqualTo(40);
        assertThat(rotated.getRGB(19, 0) & 0xFFFFFF).isEqualTo(0xFF0000);
        assertThat(halfTurn.getRGB(39, 19) & 0xFFFFFF).isEqualTo(0xFF0000);
        assertThat(ImageProcessor.orient(landscape, 1)).isSameAs(landscape);
    }
}
