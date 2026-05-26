package io.github.petomka.javatypst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link JavaTypst#renderPng} — the per-page raster output path.
 *
 * <p>Assertions are anchored on PNG file-format invariants (the 8-byte magic header), ImageIO's
 * decoded pixmap dimensions, and the standard isolation properties shared with the other render
 * methods.
 */
public class PngTest {

    /** The 8-byte signature every PNG file starts with. */
    private static final byte[] PNG_MAGIC = {
        (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
        (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A
    };

    private static byte[] customFont;

    @BeforeAll
    static void loadFontAndResetEngine() throws IOException {
        JavaTypst.reset();
        try (InputStream is = PngTest.class.getResourceAsStream("texgyrecursor-regular.otf")) {
            assertNotNull(is, "test font missing from resources");
            customFont = is.readAllBytes();
        }
    }

    /** Verifies the first 8 bytes of {@code blob} are the PNG signature. */
    private static void assertPngSignature(byte[] blob) {
        assertTrue(blob.length >= PNG_MAGIC.length, "PNG blob shorter than its own header");
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            assertEquals(PNG_MAGIC[i], blob[i], "PNG magic mismatch at byte " + i);
        }
    }

    /** Decodes a PNG via ImageIO and returns the resulting BufferedImage. */
    private static BufferedImage decodePng(byte[] blob) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(blob));
        assertNotNull(img, "ImageIO failed to decode PNG (returned null)");
        return img;
    }

    @Test
    public void singlePageDocumentProducesOnePng() throws IOException {
        List<byte[]> pages = JavaTypst.renderPng("= Hello, World!");
        assertEquals(1, pages.size(), "expected one PNG per page");
        assertPngSignature(pages.get(0));
        BufferedImage img = decodePng(pages.get(0));
        assertTrue(img.getWidth() > 0 && img.getHeight() > 0, "decoded image has zero dimensions");
    }

    @Test
    public void multiPageDocumentProducesOnePngPerPage() throws IOException {
        List<byte[]> pages = JavaTypst.renderPng("page one\n#pagebreak()\npage two");
        assertEquals(2, pages.size(), "expected one PNG per page");
        for (byte[] page : pages) {
            assertPngSignature(page);
            assertNotNull(decodePng(page));
        }
    }

    @Test
    public void renderPngConvenienceShortcutUsesDefaultOptions() throws IOException {
        List<byte[]> viaShortcut = JavaTypst.renderPng("= Same");
        List<byte[]> viaDefault = JavaTypst.renderPng("= Same", RenderOptions.DEFAULT);
        assertEquals(viaShortcut.size(), viaDefault.size());
        // Default options ⇒ identical pixmap dimensions (same DPI, same content).
        BufferedImage a = decodePng(viaShortcut.get(0));
        BufferedImage b = decodePng(viaDefault.get(0));
        assertEquals(a.getWidth(), b.getWidth());
        assertEquals(a.getHeight(), b.getHeight());
    }

    @Test
    public void higherPixelPerPtProducesProportionallyLargerImage() throws IOException {
        String src = "= Hi";
        BufferedImage atOne = decodePng(JavaTypst.renderPng(
                        src, RenderOptions.builder().pngPixelPerPt(1.0f).build())
                .get(0));
        BufferedImage atFour = decodePng(JavaTypst.renderPng(
                        src, RenderOptions.builder().pngPixelPerPt(4.0f).build())
                .get(0));
        // typst rounds, so allow ±1 px; the ratio should still be close to 4×.
        double widthRatio = (double) atFour.getWidth() / atOne.getWidth();
        double heightRatio = (double) atFour.getHeight() / atOne.getHeight();
        assertTrue(
                widthRatio > 3.5 && widthRatio < 4.5, "width ratio at 4×/1× density should be ~4, got " + widthRatio);
        assertTrue(
                heightRatio > 3.5 && heightRatio < 4.5,
                "height ratio at 4×/1× density should be ~4, got " + heightRatio);
    }

    @Test
    public void renderPngHonoursInputsAndFontsTogether() throws IOException {
        // Same orthogonality demo as SvgTest — proves the format choice doesn't shut out the
        // RenderOptions fields.
        RenderOptions opts = RenderOptions.builder()
                .inputs(Map.of("greeting", "Bonjour"))
                .fonts(List.of(customFont))
                .build();
        List<byte[]> pages =
                JavaTypst.renderPng("#set text(font: \"TeX Gyre Cursor\")\n#sys.inputs.at(\"greeting\"), monde!", opts);
        assertEquals(1, pages.size());
        assertPngSignature(pages.get(0));
        BufferedImage img = decodePng(pages.get(0));
        assertTrue(img.getWidth() > 0 && img.getHeight() > 0);
    }

    @Test
    public void renderPngPropagatesCompilationErrors() {
        assertThrows(TypstRenderException.class, () -> JavaTypst.renderPng("#let x ="));
    }

    @Test
    public void renderPngRejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> JavaTypst.renderPng(null));
        assertThrows(NullPointerException.class, () -> JavaTypst.renderPng(null, RenderOptions.DEFAULT));
        assertThrows(NullPointerException.class, () -> JavaTypst.renderPng("x", null));
    }

    @Test
    public void builderRejectsNonPositiveOrNonFinitePngPixelPerPt() {
        assertThrows(
                IllegalArgumentException.class, () -> RenderOptions.builder().pngPixelPerPt(0.0f));
        assertThrows(
                IllegalArgumentException.class, () -> RenderOptions.builder().pngPixelPerPt(-1.0f));
        assertThrows(
                IllegalArgumentException.class, () -> RenderOptions.builder().pngPixelPerPt(Float.NaN));
        assertThrows(
                IllegalArgumentException.class, () -> RenderOptions.builder().pngPixelPerPt(Float.POSITIVE_INFINITY));
    }
}
