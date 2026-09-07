# Opcode coverage

The wire format has 172 opcodes (`androidx.compose.remote.core.Operations`, remote-core
1.0.0-alpha18). This renderer decodes 138 of them; this file says what each one does here.

Three statuses, and the distinction matters — see "what supported means" in `docs/PLAN.md`:

- **supported** — decoded and acted on, with a fixture and a test behind it.
- **partial** — the common path is acted on and the rest is not. The note says which is which.
- **decoded only** — the record is read so the byte stream stays aligned and the document can be
  inspected, but nothing in the render changes because of it. Not a feature.

An opcode in neither table below is not decoded at all: a document containing one throws
`RemoteComposeParseException` at the byte where it appears, rather than silently skipping it,
because the format has no generic length prefix to skip by.

## Decoded

### Document

| Id | Opcode | Status | Notes |
| --: | --- | --- | --- |
| 0 | `HEADER` | supported |  |
| 14 | `ANIMATION_SPEC` | partial | a component that moved or resized is drawn on its way there; the enter and exit animations are not run |
| 63 | `THEME` | supported | brackets the operations belonging to one mode |
| 65 | `ROOT_CONTENT_BEHAVIOR` | decoded only | no document-level scaling or scroll mode |
| 103 | `ROOT_CONTENT_DESCRIPTION` | decoded only | accessibility text is not surfaced |
| 177 | `HAPTIC_FEEDBACK` | decoded only | no haptics |
| 179 | `DEBUG_MESSAGE` | decoded only | nothing is logged |
| 185 | `REM` | decoded only | a comment record |
| 241 | `SKIP` | supported |  |

### Data pools

| Id | Opcode | Status | Notes |
| --: | --- | --- | --- |
| 45 | `DATA_SHADER` | decoded only | uniforms are kept; painting one needs a runtime shader compiler |
| 80 | `DATA_FLOAT` | supported |  |
| 101 | `DATA_BITMAP` | supported |  |
| 102 | `DATA_TEXT` | supported |  |
| 123 | `DATA_PATH` | supported |  |
| 138 | `COLOR_CONSTANT` | supported |  |
| 180 | `ATTRIBUTE_COLOR` | supported | a colour's hue, saturation, brightness or one channel, as a float |
| 196 | `COLOR_THEME` | supported | one colour with a value for each mode |
| 140 | `DATA_INT` | supported |  |
| 143 | `DATA_BOOLEAN` | supported |  |
| 145 | `ID_MAP` | supported |  |
| 146 | `ID_LIST` | supported |  |
| 147 | `FLOAT_LIST` | supported | entries written as ids stay ids, as they do in the library |
| 197 | `DYNAMIC_FLOAT_LIST` | supported |  |
| 148 | `DATA_LONG` | supported |  |
| 167 | `DATA_BITMAP_FONT` | supported |  |

### Values and expressions

| Id | Opcode | Status | Notes |
| --: | --- | --- | --- |
| 81 | `ANIMATED_FLOAT` | partial | cubic easing; bounce, elastic, spline and directional snap are decoded only. Collection operators read a list (`A_DEREF`, `A_MAX`, `A_MIN`, `A_SUM`, `A_AVG`, `A_LEN`); `A_SPLINE` and the rest are not run |
| 134 | `COLOR_EXPRESSIONS` | supported |  |
| 135 | `TEXT_FROM_FLOAT` | supported |  |
| 136 | `TEXT_MERGE` | supported |  |
| 144 | `INTEGER_EXPRESSION` | supported |  |
| 151 | `TEXT_LOOKUP` | supported |  |
| 153 | `TEXT_LOOKUP_INT` | supported |  |
| 154 | `DATA_MAP_LOOKUP` | supported |  |
| 155 | `TEXT_MEASURE` | supported |  |
| 156 | `TEXT_LENGTH` | supported |  |
| 166 | `FUNCTION_CALL` | supported |  |
| 168 | `FUNCTION_DEFINE` | supported |  |
| 178 | `CONDITIONAL_OPERATIONS` | supported |  |
| 182 | `TEXT_SUBTEXT` | supported |  |
| 183 | `BITMAP_TEXT_MEASURE` | partial | the monospace and max-height flags are not applied |
| 192 | `ID_LOOKUP` | supported |  |
| 199 | `TEXT_TRANSFORM` | supported |  |
| 214 | `CONTAINER_END` | supported |  |
| 215 | `LOOP_START` | supported |  |
| 198 | `UPDATE_DYNAMIC_FLOAT_LIST` | supported |  |
| 244 | `MACRO_FOR_EACH` | supported | over an id list, which is the only kind that has entry ids |
| 246 | `MACRO_DEFINE` | supported |  |
| 247 | `MACRO_CALL` | supported |  |
| 248 | `MACRO_ARGUMENT` | supported |  |
| 249 | `MACRO_BLOCK` | supported |  |

### Paint

| Id | Opcode | Status | Notes |
| --: | --- | --- | --- |
| 40 | `PAINT_VALUES` | supported |  |

### Draws

| Id | Opcode | Status | Notes |
| --: | --- | --- | --- |
| 42 | `DRAW_RECT` | supported |  |
| 43 | `DRAW_TEXT_RUN` | supported |  |
| 44 | `DRAW_BITMAP` | supported |  |
| 46 | `DRAW_CIRCLE` | supported |  |
| 47 | `DRAW_LINE` | supported |  |
| 48 | `DRAW_BITMAP_FONT_TEXT_RUN` | supported |  |
| 49 | `DRAW_BITMAP_FONT_TEXT_RUN_ON_PATH` | supported |  |
| 51 | `DRAW_ROUND_RECT` | supported |  |
| 52 | `DRAW_SECTOR` | supported |  |
| 53 | `DRAW_TEXT_ON_PATH` | supported |  |
| 56 | `DRAW_OVAL` | supported |  |
| 57 | `DRAW_TEXT_ON_CIRCLE` | partial | the real operation throws at this version, so there is no reference rendering |
| 66 | `DRAW_BITMAP_INT` | supported |  |
| 124 | `DRAW_PATH` | supported |  |
| 125 | `DRAW_TWEEN_PATH` | supported |  |
| 133 | `DRAW_TEXT_ANCHOR` | supported |  |
| 149 | `DRAW_BITMAP_SCALED` | supported |  |
| 152 | `DRAW_ARC` | supported |  |

### Paths

| Id | Opcode | Status | Notes |
| --: | --- | --- | --- |
| 158 | `PATH_TWEEN` | supported |  |
| 159 | `PATH_CREATE` | supported |  |
| 160 | `PATH_ADD` | supported |  |
| 175 | `PATH_COMBINE` | supported |  |
| 193 | `PATH_EXPRESSION` | supported |  |

### Canvas state

| Id | Opcode | Status | Notes |
| --: | --- | --- | --- |
| 38 | `CLIP_PATH` | supported |  |
| 39 | `CLIP_RECT` | supported |  |
| 126 | `MATRIX_SCALE` | supported |  |
| 127 | `MATRIX_TRANSLATE` | supported |  |
| 128 | `MATRIX_SKEW` | supported |  |
| 129 | `MATRIX_ROTATE` | supported |  |
| 130 | `MATRIX_SAVE` | supported |  |
| 131 | `MATRIX_RESTORE` | supported |  |
| 173 | `CANVAS_OPERATIONS` | supported |  |
| 181 | `MATRIX_FROM_PATH` | supported |  |

### Matrices

| Id | Opcode | Status | Notes |
| --: | --- | --- | --- |
| 186 | `MATRIX_CONSTANT` | supported |  |
| 187 | `MATRIX_EXPRESSION` | supported |  |
| 188 | `MATRIX_VECTOR_MATH` | supported |  |

### Particles

| Id | Opcode | Status | Notes |
| --: | --- | --- | --- |
| 161 | `PARTICLE_DEFINE` | supported |  |
| 163 | `PARTICLE_LOOP` | supported |  |
| 194 | `PARTICLE_COMPARE` | partial | the single-equation-set form; the two-body form is decoded only |

### Layout

| Id | Opcode | Status | Notes |
| --: | --- | --- | --- |
| 93 | `LAYOUT_CUSTOM` | supported |  |
| 176 | `LAYOUT_FIT_BOX` | partial | measured as a Box; the fit scaling is not applied |
| 200 | `LAYOUT_ROOT` | supported |  |
| 201 | `LAYOUT_CONTENT` | supported |  |
| 202 | `LAYOUT_BOX` | supported |  |
| 203 | `LAYOUT_ROW` | supported |  |
| 204 | `LAYOUT_COLUMN` | supported |  |
| 205 | `LAYOUT_CANVAS` | supported |  |
| 207 | `LAYOUT_CANVAS_CONTENT` | supported |  |
| 208 | `LAYOUT_TEXT` | supported |  |
| 217 | `LAYOUT_STATE` | supported |  |
| 230 | `LAYOUT_COLLAPSIBLE_ROW` | supported |  |
| 233 | `LAYOUT_COLLAPSIBLE_COLUMN` | supported |  |
| 234 | `LAYOUT_IMAGE` | supported |  |
| 240 | `LAYOUT_FLOW` | supported |  |

### Modifiers

| Id | Opcode | Status | Notes |
| --: | --- | --- | --- |
| 16 | `MODIFIER_WIDTH` | supported |  |
| 54 | `MODIFIER_ROUNDED_CLIP_RECT` | supported |  |
| 55 | `MODIFIER_BACKGROUND` | supported |  |
| 58 | `MODIFIER_PADDING` | supported |  |
| 67 | `MODIFIER_HEIGHT` | supported |  |
| 107 | `MODIFIER_BORDER` | supported |  |
| 108 | `MODIFIER_CLIP_RECT` | supported |  |
| 174 | `MODIFIER_DRAW_CONTENT` | decoded only | content is drawn in stream order regardless |
| 211 | `MODIFIER_VISIBILITY` | supported |  |
| 221 | `MODIFIER_OFFSET` | supported |  |
| 223 | `MODIFIER_ZINDEX` | supported |  |
| 224 | `MODIFIER_GRAPHICS_LAYER` | partial | alpha, rotation, scale and translation; the rest of the attributes are ignored |
| 226 | `MODIFIER_SCROLL` | partial | the window, the offset and dragging; velocity easing and notch stops are not applied |
| 228 | `MODIFIER_MARQUEE` | decoded only | no scrolling text |
| 229 | `MODIFIER_RIPPLE` | decoded only | no press feedback |
| 231 | `MODIFIER_WIDTH_IN` | supported |  |
| 232 | `MODIFIER_HEIGHT_IN` | supported |  |
| 235 | `MODIFIER_COLLAPSIBLE_PRIORITY` | supported |  |
| 237 | `MODIFIER_ALIGN_BY` | decoded only | baseline alignment is not applied |
| 243 | `MODIFIER_DIMENSION_CONSTRAINTS` | supported |  |

### Touch and actions

| Id | Opcode | Status | Notes |
| --: | --- | --- | --- |
| 59 | `MODIFIER_CLICK` | supported |  |
| 64 | `CLICK_AREA` | supported |  |
| 83 | `MODIFIER_MULTI_CLICK` | supported |  |
| 157 | `TOUCH_EXPRESSION` | partial | default drag mode; velocity easing, wrap and notch stops are not applied |
| 209 | `HOST_ACTION` | supported |  |
| 212 | `VALUE_INTEGER_CHANGE_ACTION` | supported |  |
| 213 | `VALUE_STRING_CHANGE_ACTION` | supported |  |
| 218 | `VALUE_INTEGER_EXPRESSION_CHANGE_ACTION` | supported |  |
| 219 | `MODIFIER_TOUCH_DOWN` | supported |  |
| 220 | `MODIFIER_TOUCH_UP` | supported |  |
| 222 | `VALUE_FLOAT_CHANGE_ACTION` | supported |  |
| 225 | `MODIFIER_TOUCH_CANCEL` | supported |  |
| 227 | `VALUE_FLOAT_EXPRESSION_CHANGE_ACTION` | supported |  |


## Not decoded

A document using any of these fails to parse. They fall into groups: sound (`PLAY_SOUND`,
`DATA_SOUND`, `SOUND_EXPRESSION`), the rest of the loom system (`REFERENCED_OPERATIONS`,
`INCLUDE_REFERENCED_OPERATIONS`), the remaining attribute readers (`ATTRIBUTE_TEXT`,
`ATTRIBUTE_IMAGE`, `ATTRIBUTE_TIME`), host-named and metadata actions, accessibility semantics,
and the extension range.

| Id | Opcode |
| --: | --- |
| 2 | `COMPONENT_START` |
| 4 | `LOAD_BITMAP` |
| 132 | `MATRIX_SET` |
| 137 | `NAMED_VARIABLE` |
| 139 | `DRAW_CONTENT` |
| 141 | `PLAY_SOUND` |
| 142 | `REFERENCED_OPERATIONS` |
| 150 | `COMPONENT_VALUE` |
| 162 | `PARTICLE_PROCESS` |
| 164 | `IMPULSE_START` |
| 165 | `IMPULSE_PROCESS` |
| 169 | `DATA_SOUND` |
| 170 | `ATTRIBUTE_TEXT` |
| 171 | `ATTRIBUTE_IMAGE` |
| 172 | `ATTRIBUTE_TIME` |
| 184 | `DRAW_BITMAP_TEXT_ANCHORED` |
| 189 | `DATA_FONT` |
| 190 | `DRAW_TO_BITMAP` |
| 191 | `WAKE_IN` |
| 195 | `UPDATE` |
| 206 | `SOUND_EXPRESSION` |
| 210 | `HOST_NAMED_ACTION` |
| 216 | `HOST_METADATA_ACTION` |
| 236 | `RUN_ACTION` |
| 238 | `LAYOUT_COMPUTE` |
| 239 | `CORE_TEXT` |
| 242 | `TEXT_STYLE` |
| 245 | `INCLUDE_REFERENCED_OPERATIONS` |
| 250 | `ACCESSIBILITY_SEMANTICS` |
| 251 | `EXTENSION_RANGE_RESERVED_4` |
| 252 | `EXTENSION_RANGE_RESERVED_3` |
| 253 | `EXTENSION_RANGE_RESERVED_2` |
| 254 | `EXTENSION_RANGE_RESERVED_1` |
| 255 | `EXTENDED_OPCODE` |

## Paint attributes

`PAINT_VALUES` (40) carries a `PaintBundle`, whose attributes are decoded by
`PaintBundleDecoder`. Colour, alpha, style, stroke width, cap, join, miter, text size, typeface,
blend mode and all three gradient kinds are applied. These are read to keep the bundle aligned
and then ignored: `SHADER` (only a clear takes effect), `SHADER_MATRIX`, `COLOR_FILTER`,
`COLOR_FILTER_ID`, `CLEAR_COLOR_FILTER`, `IMAGE_FILTER_QUALITY`, `ANTI_ALIAS`, `FILTER_BITMAP`,
`FONT_AXIS`, `TEXTURE`, `PATH_EFFECT` and `FALLBACK_TYPEFACE`.
