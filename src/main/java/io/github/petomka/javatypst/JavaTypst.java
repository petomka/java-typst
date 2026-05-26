package io.github.petomka.javatypst;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JavaTypst {

    // ── WASM instance (initialized once, under LOCK) ─────────────────────────

    private static volatile Instance instance;
    private static volatile ExportFunction allocFn;
    private static volatile ExportFunction deallocFn;
    private static volatile ExportFunction renderFn;
    private static volatile ExportFunction lastErrPtrFn;
    private static volatile ExportFunction lastErrLenFn;

    private static final Object LOCK = new Object();

    private static volatile boolean aotEnabled = false;

    // ── TLV tag and output-format wire constants (must mirror lib.rs) ────────

    private static final int TAG_INPUTS = 1;
    private static final int TAG_FONTS = 2;
    private static final int TAG_OUTPUT_FORMAT = 3;
    private static final int TAG_PNG_PIXEL_PER_PT = 4;

    private static final int FORMAT_PDF = 1;
    private static final int FORMAT_SVG = 2;
    private static final int FORMAT_PNG = 3;

    // ── Package resolution configuration ─────────────────────────────────────

    private static volatile TypstPackageResolver packageResolver = new HttpPackageResolver();
    private static volatile Path packageCacheDir = defaultCacheDir();
    private static volatile PackageDiskCache diskCache;

    // Set for the duration of a render whose options carry an air-gapped package map; null means
    // "use the HTTP fallback via diskCache + packageResolver."
    private static Map<String, byte[]> currentPackageUrlMap = null;

    // Holds fetched bytes between the two-call host protocol (size-query then data-write).
    // Safe without synchronization because all rendering is serialized under LOCK.
    private static final Map<String, byte[]> pendingFetches = new HashMap<>();

    // ── Public configuration API ──────────────────────────────────────────────

    /**
     * Replaces the package resolver used for HTTP downloads.
     * Must be called before the first render if you also call
     * {@link #setPackageCacheDirectory}; otherwise may be called at any time.
     */
    public static void setPackageResolver(TypstPackageResolver resolver) {
        if (resolver == null) throw new NullPointerException("resolver");
        synchronized (LOCK) {
            packageResolver = resolver;
        }
    }

    /**
     * Overrides the disk cache directory (default: {@code $XDG_CACHE_HOME/java-typst/packages}).
     * Must be called before the first render call.
     */
    public static void setPackageCacheDirectory(Path dir) {
        if (dir == null) throw new NullPointerException("dir");
        synchronized (LOCK) {
            if (instance != null) {
                throw new IllegalStateException("setPackageCacheDirectory must be called before the first render");
            }
            packageCacheDir = dir;
        }
    }

    /**
     * Switches to the AOT-compiled machine. Must be called before the first render.
     */
    public static void enableAot() {
        synchronized (LOCK) {
            if (instance != null) {
                throw new IllegalStateException("enableAot must be called before the first render");
            }
            aotEnabled = true;
        }
    }

    /** Tears down the current instance so it will be re-initialized on the next render. For testing only. */
    static void reset() {
        synchronized (LOCK) {
            instance = null;
            allocFn = null;
            deallocFn = null;
            renderFn = null;
            lastErrPtrFn = null;
            lastErrLenFn = null;
            aotEnabled = false;
            diskCache = null;
        }
    }

    // ── Public render API ─────────────────────────────────────────────────────

    /**
     * Renders Typst markup to a PDF with default options. Shorthand for
     * {@code renderPdf(content, RenderOptions.DEFAULT)}.
     *
     * @param content Typst source (must not be null)
     * @return PDF as a byte array
     * @throws TypstRenderException if compilation fails
     */
    public static byte[] renderPdf(String content) {
        return renderPdf(content, RenderOptions.DEFAULT);
    }

    /**
     * Renders Typst markup to a PDF with the given options.
     *
     * @param content Typst source (must not be null)
     * @param options render options (must not be null; use {@link RenderOptions#DEFAULT} for none)
     * @return PDF as a byte array
     * @throws TypstRenderException if compilation fails or a required package is absent from an
     *                              air-gapped package map
     */
    public static byte[] renderPdf(String content, RenderOptions options) {
        List<byte[]> result = executeRender(content, options, FORMAT_PDF);
        if (result.size() != 1) {
            throw new IllegalStateException("PDF render produced " + result.size() + " blobs, expected 1");
        }
        return result.get(0);
    }

    /**
     * Renders Typst markup to a list of SVG documents, one per page, with default options.
     * Shorthand for {@code renderSvg(content, RenderOptions.DEFAULT)}.
     *
     * @param content Typst source (must not be null)
     * @return one UTF-8 SVG document per page, in document order
     * @throws TypstRenderException if compilation fails
     */
    public static List<byte[]> renderSvg(String content) {
        return renderSvg(content, RenderOptions.DEFAULT);
    }

    /**
     * Renders Typst markup to a list of SVG documents, one per page, with the given options.
     *
     * <p>Each list entry is a complete, standalone SVG document encoded as UTF-8. Typst renders
     * text as path outlines, not as {@code <text>} elements, so the resulting SVG is portable but
     * not text-searchable.
     *
     * @param content Typst source (must not be null)
     * @param options render options (must not be null; use {@link RenderOptions#DEFAULT} for none)
     * @return one UTF-8 SVG document per page, in document order
     * @throws TypstRenderException if compilation fails or a required package is absent from an
     *                              air-gapped package map
     */
    public static List<byte[]> renderSvg(String content, RenderOptions options) {
        return executeRender(content, options, FORMAT_SVG);
    }

    /**
     * Renders Typst markup to a list of PNG images, one per page, with default options.
     * Shorthand for {@code renderPng(content, RenderOptions.DEFAULT)}.
     *
     * <p>Pixel density defaults to {@link RenderOptions#DEFAULT_PNG_PIXEL_PER_PT} (≈ 144 DPI);
     * override via {@link RenderOptions.Builder#pngPixelPerPt}.
     *
     * @param content Typst source (must not be null)
     * @return one PNG image per page, in document order
     * @throws TypstRenderException if compilation fails
     */
    public static List<byte[]> renderPng(String content) {
        return renderPng(content, RenderOptions.DEFAULT);
    }

    /**
     * Renders Typst markup to a list of PNG images, one per page, with the given options.
     *
     * @param content Typst source (must not be null)
     * @param options render options (must not be null; use {@link RenderOptions#DEFAULT} for none)
     * @return one PNG image per page, in document order
     * @throws TypstRenderException if compilation fails or a required package is absent from an
     *                              air-gapped package map
     */
    public static List<byte[]> renderPng(String content, RenderOptions options) {
        return executeRender(content, options, FORMAT_PNG);
    }

    // ── Initialization ────────────────────────────────────────────────────────

    // Caller must hold LOCK.
    private static void ensureInitialized() {
        if (instance != null) return;
        try {
            diskCache = new PackageDiskCache(packageCacheDir);

            var wasi = WasiPreview1.builder()
                    .withOptions(WasiOptions.builder().build())
                    .build();

            var fetchFn = new HostFunction(
                    "java_typst_host",
                    "host_fetch_url",
                    FunctionType.of(List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of(ValType.I32)),
                    (Instance inst, long... args) -> {
                        int urlPtr = (int) args[0], urlLen = (int) args[1];
                        int outBuf = (int) args[2], outCap = (int) args[3];
                        Memory mem = inst.memory();
                        String url = mem.readString(urlPtr, urlLen);
                        if (outBuf == 0) {
                            try {
                                byte[] bytes = fetchPackage(url);
                                pendingFetches.put(url, bytes);
                                return new long[] {bytes.length};
                            } catch (Exception ex) {
                                return new long[] {-1};
                            }
                        }
                        byte[] bytes = pendingFetches.remove(url);
                        if (bytes == null) return new long[] {-1};
                        int len = Math.min(bytes.length, outCap);
                        mem.write(outBuf, Arrays.copyOf(bytes, len));
                        return new long[] {len};
                    });

            Instance newInstance;
            if (aotEnabled) {
                var imports = ImportValues.builder()
                        .addFunction(wasi.toHostFunctions())
                        .addFunction(fetchFn)
                        .build();
                newInstance = Instance.builder(JavaTypstModule.load())
                        .withMachineFactory(JavaTypstModule::create)
                        .withImportValues(imports)
                        .build();
            } else {
                try (InputStream stream =
                        JavaTypst.class.getResourceAsStream("/io/github/petomka/javatypst/java_typst.wasm")) {
                    if (stream == null) throw new RuntimeException("java_typst.wasm not found on classpath");
                    var store = new Store().addFunction(wasi.toHostFunctions()).addFunction(fetchFn);
                    newInstance = store.instantiate("java-typst", Parser.parse(stream));
                }
            }

            allocFn = newInstance.export("alloc");
            deallocFn = newInstance.export("dealloc");
            renderFn = newInstance.export("render");
            lastErrPtrFn = newInstance.export("last_error_ptr");
            lastErrLenFn = newInstance.export("last_error_len");
            instance = newInstance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize JavaTypst", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Single entry point shared by {@link #renderPdf}, {@link #renderSvg}, {@link #renderPng}.
     * Encodes the options blob (including the chosen format), takes the WASM lock, calls the
     * single {@code render} export, and parses the TLV result into one byte array per output
     * blob (one for PDF, one per page for SVG/PNG).
     */
    private static List<byte[]> executeRender(String content, RenderOptions options, int formatId) {
        if (content == null) throw new NullPointerException("content");
        if (options == null) throw new NullPointerException("options");
        byte[] optsBlob = encodeOptions(options, formatId);
        Map<String, byte[]> airGappedPackages = options.packagesOrNull();
        synchronized (LOCK) {
            Map<String, byte[]> urlMap = null;
            if (airGappedPackages != null) {
                urlMap = new HashMap<>();
                for (Map.Entry<String, byte[]> e : airGappedPackages.entrySet()) {
                    urlMap.put(specToUrl(e.getKey()), e.getValue());
                }
            }
            currentPackageUrlMap = urlMap;
            try {
                byte[] resultBlob = renderUnderLock(content, optsBlob);
                return decodeResultBlob(resultBlob);
            } finally {
                currentPackageUrlMap = null;
            }
        }
    }

    /** Caller must hold LOCK. Returns the raw TLV result blob produced by the WASM render. */
    private static byte[] renderUnderLock(String content, byte[] optsBlob) {
        ensureInitialized();
        Memory memory = instance.memory();

        byte[] srcBytes = content.getBytes(StandardCharsets.UTF_8);
        int srcLen = srcBytes.length;
        int optsLen = optsBlob.length;
        int srcPtr = (int) allocFn.apply(srcLen)[0];
        int optsPtr = (int) allocFn.apply(optsLen)[0];
        int outLenPtr = (int) allocFn.apply(4)[0];
        try {
            memory.write(srcPtr, srcBytes);
            memory.write(optsPtr, optsBlob);
            int outPtr = (int) renderFn.apply(srcPtr, srcLen, optsPtr, optsLen, outLenPtr)[0];
            if (outPtr == 0) {
                int errPtr = (int) lastErrPtrFn.apply()[0];
                int errLen = (int) lastErrLenFn.apply()[0];
                String errorMsg = memory.readString(errPtr, errLen);
                throw new TypstRenderException(errorMsg);
            }
            int outLen = memory.readInt(outLenPtr);
            byte[] resultBytes = memory.readBytes(outPtr, outLen);
            deallocFn.apply(outPtr, outLen);
            return resultBytes;
        } finally {
            deallocFn.apply(outLenPtr, 4);
            deallocFn.apply(optsPtr, optsLen);
            deallocFn.apply(srcPtr, srcLen);
            pendingFetches.clear();
        }
    }

    /**
     * Encodes a {@link RenderOptions} plus the chosen format into the TLV blob consumed by the
     * {@code render} WASM export. Layout:
     *
     * <pre>
     * field_count:u32   then, repeated field_count times:
     *   tag:u32   1 = inputs, 2 = fonts, 3 = output format, 4 = png_pixel_per_pt
     *   len:u32   length of the payload in bytes
     *   payload:bytes   tag-specific (see encodeInputs / encodeFonts / encodeFormat / encodeFloat)
     * </pre>
     *
     * Empty optional fields (inputs / fonts) are omitted entirely. The output format and PNG
     * density tags are always emitted so the wire format is unambiguous and changes to Rust-side
     * defaults can never surprise the host.
     */
    private static byte[] encodeOptions(RenderOptions options, int formatId) {
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        int fieldCount = 0;

        if (!options.inputs().isEmpty()) {
            byte[] payload = encodeInputs(options.inputs());
            writeLittleEndianInt(fields, TAG_INPUTS);
            writeLittleEndianInt(fields, payload.length);
            fields.writeBytes(payload);
            fieldCount++;
        }
        if (!options.fonts().isEmpty()) {
            byte[] payload = encodeFonts(options.fonts());
            writeLittleEndianInt(fields, TAG_FONTS);
            writeLittleEndianInt(fields, payload.length);
            fields.writeBytes(payload);
            fieldCount++;
        }
        // Output format — always emitted.
        writeLittleEndianInt(fields, TAG_OUTPUT_FORMAT);
        writeLittleEndianInt(fields, 4);
        writeLittleEndianInt(fields, formatId);
        fieldCount++;
        // PNG pixel-per-point — always emitted (ignored by non-PNG formats on the Rust side).
        writeLittleEndianInt(fields, TAG_PNG_PIXEL_PER_PT);
        writeLittleEndianInt(fields, 4);
        writeLittleEndianInt(fields, Float.floatToRawIntBits(options.pngPixelPerPt()));
        fieldCount++;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLittleEndianInt(out, fieldCount);
        out.writeBytes(fields.toByteArray());
        return out.toByteArray();
    }

    /**
     * Wire format for {@code sys.inputs}: u32 entry count, then for each entry a length-prefixed
     * UTF-8 key followed by a length-prefixed UTF-8 value. All integers little-endian.
     */
    private static byte[] encodeInputs(Map<String, String> inputs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLittleEndianInt(out, inputs.size());
        for (Map.Entry<String, String> entry : inputs.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null) throw new NullPointerException("input key must not be null");
            if (value == null) throw new NullPointerException("input value must not be null (key: " + key + ")");
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
            writeLittleEndianInt(out, keyBytes.length);
            out.writeBytes(keyBytes);
            writeLittleEndianInt(out, valueBytes.length);
            out.writeBytes(valueBytes);
        }
        return out.toByteArray();
    }

    /**
     * Wire format for custom fonts: u32 entry count, then for each entry a u32 length followed
     * by that many raw font bytes. All integers little-endian.
     */
    private static byte[] encodeFonts(List<byte[]> fonts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLittleEndianInt(out, fonts.size());
        for (byte[] font : fonts) {
            if (font == null) throw new NullPointerException("font bytes must not be null");
            writeLittleEndianInt(out, font.length);
            out.writeBytes(font);
        }
        return out.toByteArray();
    }

    private static void writeLittleEndianInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    /**
     * Decodes the TLV result blob produced by the WASM {@code render} export:
     * {@code count:u32 ⟨len:u32, bytes⟩*}. Returns one byte array per blob, in order.
     */
    private static List<byte[]> decodeResultBlob(byte[] blob) {
        List<byte[]> result = new ArrayList<>();
        int pos = 0;
        if (blob.length < 4) {
            throw new TypstRenderException("render result truncated: " + blob.length + " bytes");
        }
        int count = readLittleEndianInt(blob, pos);
        pos += 4;
        for (int i = 0; i < count; i++) {
            if (pos + 4 > blob.length) {
                throw new TypstRenderException("render result truncated reading blob length");
            }
            int len = readLittleEndianInt(blob, pos);
            pos += 4;
            if (pos + len > blob.length) {
                throw new TypstRenderException("render result truncated reading blob payload");
            }
            byte[] item = new byte[len];
            System.arraycopy(blob, pos, item, 0, len);
            pos += len;
            result.add(item);
        }
        return result;
    }

    private static int readLittleEndianInt(byte[] buf, int pos) {
        return (buf[pos] & 0xFF)
                | ((buf[pos + 1] & 0xFF) << 8)
                | ((buf[pos + 2] & 0xFF) << 16)
                | ((buf[pos + 3] & 0xFF) << 24);
    }

    private static byte[] fetchPackage(String url) throws TypstPackageNotFoundException {
        if (currentPackageUrlMap != null) {
            byte[] bytes = currentPackageUrlMap.get(url);
            if (bytes == null) {
                String[] p = parsePackageUrl(url);
                throw new TypstPackageNotFoundException(p[0], p[1], p[2]);
            }
            return bytes;
        }
        String[] p = parsePackageUrl(url);
        return diskCache.get(p[0], p[1], p[2], packageResolver);
    }

    /** Parses "https://packages.typst.org/preview/cetz-0.3.2.tar.gz" → ["preview","cetz","0.3.2"] */
    private static String[] parsePackageUrl(String url) {
        String path = url.substring("https://packages.typst.org/".length());
        int slash = path.indexOf('/');
        String namespace = path.substring(0, slash);
        String filename = path.substring(slash + 1, path.length() - ".tar.gz".length());
        int lastHyphen = filename.lastIndexOf('-');
        String name = filename.substring(0, lastHyphen);
        String version = filename.substring(lastHyphen + 1);
        return new String[] {namespace, name, version};
    }

    /** Converts "@preview/cetz:0.3.2" → "https://packages.typst.org/preview/cetz-0.3.2.tar.gz" */
    private static String specToUrl(String spec) {
        String s = spec.startsWith("@") ? spec.substring(1) : spec;
        int colon = s.lastIndexOf(':');
        String version = s.substring(colon + 1);
        String nsAndName = s.substring(0, colon);
        int slash = nsAndName.indexOf('/');
        String namespace = nsAndName.substring(0, slash);
        String name = nsAndName.substring(slash + 1);
        return "https://packages.typst.org/" + namespace + "/" + name + "-" + version + ".tar.gz";
    }

    private static Path defaultCacheDir() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = (xdg != null && !xdg.isEmpty()) ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("java-typst/packages");
    }

    private JavaTypst() {}
}
