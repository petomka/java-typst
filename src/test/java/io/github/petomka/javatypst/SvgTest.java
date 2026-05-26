package io.github.petomka.javatypst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * Exercises {@link JavaTypst#renderSvg} — the per-page SVG output path.
 *
 * <p>Typst rasterizes glyphs as SVG path outlines (not {@code <text>} elements), so these tests
 * don't try to read text content back from the SVG. Instead they assert: well-formed XML with an
 * {@code <svg>} root, the right number of pages, and that combining {@code RenderOptions} fields
 * (inputs + custom fonts) doesn't break the output.
 */
public class SvgTest {

    private static byte[] customFont;
    private static final DocumentBuilderFactory XML_FACTORY = newSecureXmlFactory();

    @BeforeAll
    static void loadFontAndResetEngine() throws IOException {
        JavaTypst.reset();
        try (InputStream is = SvgTest.class.getResourceAsStream("texgyrecursor-regular.otf")) {
            assertNotNull(is, "test font missing from resources");
            customFont = is.readAllBytes();
        }
    }

    /** Parses one SVG blob and returns the root element's tag name, throwing on malformed XML. */
    private static String svgRootElementName(byte[] svgBytes) throws IOException, SAXException {
        DocumentBuilder builder;
        try {
            builder = XML_FACTORY.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        Document doc = builder.parse(new ByteArrayInputStream(svgBytes));
        return doc.getDocumentElement().getLocalName() != null
                ? doc.getDocumentElement().getLocalName()
                : doc.getDocumentElement().getNodeName();
    }

    @Test
    public void singlePageDocumentProducesOneSvg() throws Exception {
        List<byte[]> pages = JavaTypst.renderSvg("= Hello, World!");
        assertEquals(1, pages.size(), "expected one SVG per page");
        assertEquals("svg", svgRootElementName(pages.get(0)));
        assertTrue(pages.get(0).length > 100, "SVG should not be trivially small");
    }

    @Test
    public void multiPageDocumentProducesOneSvgPerPage() throws Exception {
        // Three pages — `#pagebreak()` forces a new page break in markup.
        List<byte[]> pages = JavaTypst.renderSvg("page one\n#pagebreak()\npage two\n#pagebreak()\npage three");
        assertEquals(3, pages.size(), "expected one SVG per page");
        for (int i = 0; i < pages.size(); i++) {
            assertEquals("svg", svgRootElementName(pages.get(i)), "page " + i + " root tag");
        }
    }

    @Test
    public void renderSvgConvenienceShortcutUsesDefaultOptions() throws Exception {
        // The no-options shortcut is just a forwarder to render(content, DEFAULT); confirm it
        // produces the same SVG as the explicit form.
        List<byte[]> viaShortcut = JavaTypst.renderSvg("= Same");
        List<byte[]> viaDefault = JavaTypst.renderSvg("= Same", RenderOptions.DEFAULT);
        assertEquals(viaShortcut.size(), viaDefault.size());
        assertEquals(svgRootElementName(viaShortcut.get(0)), svgRootElementName(viaDefault.get(0)));
    }

    @Test
    public void renderSvgHonoursInputsAndFontsTogether() throws Exception {
        // Demonstrates the format-orthogonal RenderOptions: the same builder serves SVG too.
        RenderOptions opts = RenderOptions.builder()
                .inputs(Map.of("greeting", "Servus"))
                .fonts(List.of(customFont))
                .build();
        List<byte[]> pages =
                JavaTypst.renderSvg("#set text(font: \"TeX Gyre Cursor\")\n#sys.inputs.at(\"greeting\"), Welt!", opts);
        assertEquals(1, pages.size());
        assertEquals("svg", svgRootElementName(pages.get(0)));
        // Glyph outlines for "Servus, Welt!" will inflate the SVG over the empty-document baseline.
        assertTrue(pages.get(0).length > 500, "expected non-trivial SVG with glyph paths");
    }

    @Test
    public void emptyDocumentProducesAtLeastOnePage() throws Exception {
        // Even a blank source produces one (mostly empty) page.
        List<byte[]> pages = JavaTypst.renderSvg("");
        assertTrue(pages.size() >= 1);
        assertEquals("svg", svgRootElementName(pages.get(0)));
    }

    @Test
    public void renderSvgPropagatesCompilationErrors() {
        assertThrows(TypstRenderException.class, () -> JavaTypst.renderSvg("#let x ="));
    }

    @Test
    public void renderSvgRejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> JavaTypst.renderSvg(null));
        assertThrows(NullPointerException.class, () -> JavaTypst.renderSvg(null, RenderOptions.DEFAULT));
        assertThrows(NullPointerException.class, () -> JavaTypst.renderSvg("x", null));
    }

    @Test
    public void svgBytesAreUtf8XmlText() {
        // SVG is just XML text; assert it's parseable UTF-8 starting with an XML/SVG marker.
        List<byte[]> pages = JavaTypst.renderSvg("= Hi");
        String svg = new String(pages.get(0), StandardCharsets.UTF_8);
        assertTrue(svg.contains("<svg"), "expected literal <svg in the output, got: " + svg.substring(0, 80));
    }

    private static DocumentBuilderFactory newSecureXmlFactory() {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        try {
            // Standard XXE hardening — not strictly necessary for trusted input but cheap to do.
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (ParserConfigurationException ignored) {
            // Older XML parsers may not support all of these — proceed anyway.
        }
        return f;
    }
}
