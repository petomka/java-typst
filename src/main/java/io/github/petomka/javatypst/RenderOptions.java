package io.github.petomka.javatypst;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable bundle of optional knobs that {@link JavaTypst#renderPdf(String, RenderOptions)},
 * {@link JavaTypst#renderSvg(String, RenderOptions)}, and
 * {@link JavaTypst#renderPng(String, RenderOptions)} all accept.
 *
 * <p>Every field is optional. The intent is that adding a new render-time knob (e.g. an
 * alternate PDF profile, a custom file resolver, a logging hook) only requires adding a new
 * builder method and a new accessor here — never a new render method, never a new WASM export,
 * and never a combinatorial product across feature axes. The choice of <i>output format</i> is
 * baked into the method name (PDF / SVG / PNG), keeping the return type natural for each.
 *
 * <h2>What each field does</h2>
 * <ul>
 *   <li><b>{@code inputs}</b> — exposed inside the document as {@code sys.inputs}, mirroring
 *       {@code typst compile --input key=value}. Defaults to an empty map.</li>
 *   <li><b>{@code fonts}</b> — raw OTF/TTF/TTC byte arrays added alongside the typst-kit
 *       embedded fonts. Defaults to an empty list (only embedded fonts are used).</li>
 *   <li><b>{@code packages}</b> — switches the engine to <i>air-gapped</i> mode. When set, the
 *       supplied {@code "@namespace/name:version"} → tarball map is the <i>only</i> package
 *       source: imports not found in the map fail with {@link TypstRenderException} and no HTTP
 *       request is made. When left unset (the default), the engine falls back to fetching
 *       packages from {@code packages.typst.org} via the configured
 *       {@link TypstPackageResolver} and on-disk cache.</li>
 *   <li><b>{@code pngPixelPerPt}</b> — pixels-per-typst-point for PNG output (default {@code 2.0},
 *       ≈ 144 DPI, matching the Typst CLI's default). Silently ignored by
 *       {@link JavaTypst#renderPdf} and {@link JavaTypst#renderSvg} — they don't rasterize.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * byte[] pdf = JavaTypst.renderPdf(
 *         "Hello, #sys.inputs.at(\"name\")!",
 *         RenderOptions.builder()
 *                 .inputs(Map.of("name", "World"))
 *                 .fonts(List.of(myFontBytes))
 *                 .build());
 * }</pre>
 */
public final class RenderOptions {

    /** Default value of {@link Builder#pngPixelPerPt}: 2.0 (≈ 144 DPI). */
    public static final float DEFAULT_PNG_PIXEL_PER_PT = 2.0f;

    /** A no-op options instance: no inputs, no fonts, HTTP package fallback, default PNG DPI. */
    public static final RenderOptions DEFAULT = new RenderOptions(Map.of(), List.of(), null, DEFAULT_PNG_PIXEL_PER_PT);

    private final Map<String, String> inputs;
    private final List<byte[]> fonts;
    private final Map<String, byte[]> packages;
    private final float pngPixelPerPt;

    private RenderOptions(
            Map<String, String> inputs, List<byte[]> fonts, Map<String, byte[]> packages, float pngPixelPerPt) {
        this.inputs = inputs;
        this.fonts = fonts;
        this.packages = packages;
        this.pngPixelPerPt = pngPixelPerPt;
    }

    /** Inputs exposed as {@code sys.inputs} (never null; may be empty). */
    public Map<String, String> inputs() {
        return inputs;
    }

    /** Custom font file contents added to the engine (never null; may be empty). */
    public List<byte[]> fonts() {
        return fonts;
    }

    /**
     * Air-gapped package map keyed by {@code "@namespace/name:version"}, or {@code null} when
     * the engine should fall back to its {@link TypstPackageResolver} for package fetches.
     */
    public Map<String, byte[]> packagesOrNull() {
        return packages;
    }

    /**
     * Pixels per typst point used by {@link JavaTypst#renderPng}. Ignored by the other formats.
     * Defaults to {@link #DEFAULT_PNG_PIXEL_PER_PT}.
     */
    public float pngPixelPerPt() {
        return pngPixelPerPt;
    }

    /** Creates a fresh builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder for {@link RenderOptions}. Not thread-safe; use one builder per render. */
    public static final class Builder {
        private Map<String, String> inputs = Map.of();
        private List<byte[]> fonts = List.of();
        private Map<String, byte[]> packages = null;
        private float pngPixelPerPt = DEFAULT_PNG_PIXEL_PER_PT;

        private Builder() {}

        /**
         * Sets the map exposed as {@code sys.inputs}. Must not be null. Calling this twice
         * replaces the previous value; not calling it leaves {@code sys.inputs} empty.
         */
        public Builder inputs(Map<String, String> inputs) {
            this.inputs = Objects.requireNonNull(inputs, "inputs");
            return this;
        }

        /**
         * Sets the list of custom font files (raw OTF/TTF/TTC bytes). Must not be null.
         * Individual entries also must not be null. Not calling this leaves the typst-kit
         * embedded fonts as the only source.
         */
        public Builder fonts(List<byte[]> fonts) {
            this.fonts = Objects.requireNonNull(fonts, "fonts");
            return this;
        }

        /**
         * Switches the engine into <i>air-gapped</i> mode for this render: only the supplied
         * packages are visible to the document, and no HTTP request will be made. Pass an empty
         * map to forbid all package imports. Must not be null — to <i>opt out</i> of air-gapped
         * mode, simply don't call this method.
         */
        public Builder packages(Map<String, byte[]> packages) {
            this.packages = Objects.requireNonNull(packages, "packages");
            return this;
        }

        /**
         * Sets pixels per typst point for {@link JavaTypst#renderPng}. Must be a finite positive
         * number; values above ~10 will produce very large pixmaps. Defaults to
         * {@link #DEFAULT_PNG_PIXEL_PER_PT} (≈ 144 DPI).
         */
        public Builder pngPixelPerPt(float pixelPerPt) {
            if (!Float.isFinite(pixelPerPt) || pixelPerPt <= 0f) {
                throw new IllegalArgumentException("pngPixelPerPt must be a finite positive value, got " + pixelPerPt);
            }
            this.pngPixelPerPt = pixelPerPt;
            return this;
        }

        public RenderOptions build() {
            return new RenderOptions(inputs, fonts, packages, pngPixelPerPt);
        }
    }
}
