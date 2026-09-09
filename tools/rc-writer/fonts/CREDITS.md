# Fonts used by the demos

## Rubik Mono One (page: font)

`RubikMonoOne-subset.ttf` is Rubik Mono One, by Hubert & Fischer, under the SIL Open Font
Licence 1.1 — see `OFL.txt`, which is the licence as published with the family in
[google/fonts](https://github.com/google/fonts/tree/main/ofl/rubikmonoone). The font's own name
table carries the same copyright line and a link to the licence, so the notice travels inside the
`.rc` document as well as beside it.

It is a subset: 1.9 kB holding only the glyphs the demo draws, produced by asking the Google
Fonts API for exactly that text.

    curl -A "<a user agent old enough to be served TrueType rather than WOFF2>" \
      "https://fonts.googleapis.com/css?family=Rubik+Mono+One&text=Embedded"

then downloading the URL in the `@font-face` rule it answers with. Subsetting is a modification,
which the OFL allows; the reserved-name clause does not bite because nothing here is renamed.

A heavy slab face was chosen on purpose: the demo draws the same word twice, once in the
platform's default and once in this, so a screenshot shows at a glance whether the embedded font
was actually used.
