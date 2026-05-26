# Java Typst

A library for rendering [Typst](https://typst.app/) markup from Java. Internally,
it exposes the Typst library as a webassembly module, which is then executed
using [Chicory](https://chicory.dev/) to eliminate any runtime binary
dependencies. It even supports online Typst packages.

This is a fork of [fatihcatalkaya/java-typst](https://github.com/fatihcatalkaya/java-typst)
published under a different Maven coordinate. It adds:

- **`sys.inputs`** — pass a `Map<String, String>` that becomes available inside the document
  as `sys.inputs`, mirroring `typst compile --input key=value`.
- **Custom fonts** — register caller-supplied font files (raw OTF/TTF/TTC bytes) alongside the
  embedded fonts; no font directory is scanned.
- **Multiple output formats** — PDF, SVG (one per page), and PNG (one per page).
- **One options bag, one render method per format** — `renderPdf` / `renderSvg` / `renderPng`
  all accept the same `RenderOptions` builder, so combining features (inputs + fonts + air-gapped
  packages) is one call and not a cartesian product of overloads.

## Usage

```xml
<dependency>
    <groupId>io.github.petomka</groupId>
    <artifactId>java-typst</artifactId>
    <version>2.0.0</version>
</dependency>
```

```java
// PDF (single blob)
byte[] pdf = JavaTypst.renderPdf("= Hello, World!\n_Lorem_ *ipsum*");

// SVG (one document per page)
List<byte[]> svgPages = JavaTypst.renderSvg("page 1\n#pagebreak()\npage 2");

// PNG (one image per page; default density ≈ 144 DPI)
List<byte[]> pngPages = JavaTypst.renderPng(
        "= Headline",
        RenderOptions.builder()
                .pngPixelPerPt(3.0f)        // ≈ 216 DPI
                .build());

// All knobs at once — same RenderOptions works with every render method
byte[] fontBytes = Files.readAllBytes(Path.of("MyFont.otf"));
byte[] customized = JavaTypst.renderPdf(
        "#set text(font: \"My Font\")\nHello, #sys.inputs.at(\"name\")!",
        RenderOptions.builder()
                .inputs(Map.of("name", "World"))
                .fonts(List.of(fontBytes))
                .packages(Map.of("@preview/testpkg:0.1.0", testpkgTarGzBytes))
                .build());
```

### `RenderOptions` fields (all optional)

| field | default | meaning |
|---|---|---|
| `inputs(Map<String, String>)` | empty | exposed as `sys.inputs` in the document |
| `fonts(List<byte[]>)` | empty | raw OTF/TTF/TTC files added to the engine; family names shadow embedded fonts |
| `packages(Map<String, byte[]>)` | unset | switches to **air-gapped** mode — only the supplied `"@ns/name:version" → tar.gz` map is visible, no HTTP fetch attempted. Unset = HTTP fallback via `packages.typst.org` and the local disk cache |
| `pngPixelPerPt(float)` | `2.0` (≈144 DPI) | raster density for `renderPng`; ignored by `renderPdf` and `renderSvg` |

## Migrating from 1.x

The 1.x API had a single `render` method that returned `byte[]` (PDF). 2.0 splits by output
format:

| 1.x | 2.0 |
|---|---|
| `JavaTypst.render(content)` | `JavaTypst.renderPdf(content)` |
| `JavaTypst.render(content, options)` | `JavaTypst.renderPdf(content, options)` |
| — | `JavaTypst.renderSvg(content[, options])` → `List<byte[]>` |
| — | `JavaTypst.renderPng(content[, options])` → `List<byte[]>` |

Mechanical migration: `sed 's/render(/renderPdf(/g'` over your call sites. `RenderOptions`
itself is unchanged.

## Building from source

Requires a Rust toolchain with the `wasm32-wasip1` target:

```bash
rustup target add wasm32-wasip1
mvn -Prust package
```

Additionally, an installation of [wasm-opt](https://github.com/WebAssembly/binaryen) must be available.
