# Opcode coverage

The wire format has 172 opcodes (`androidx.compose.remote.core.Operations`, remote-core
1.0.0-alpha18). This renderer decodes 154 of them; this file says what each one does here.

Three statuses, and the distinction matters:

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
| 14 | `ANIMATION_SPEC` | partial | a component that moved or resized is drawn on its way there, and one appearing or disappearing fades; the slide, rotate and particle animations are not run |
| 63 | `THEME` | supported | brackets the operations belonging to one mode |
| 65 | `ROOT_CONTENT_BEHAVIOR` | decoded only | no document-level scaling or scroll mode |
| 103 | `ROOT_CONTENT_DESCRIPTION` | decoded only | accessibility text is not surfaced |
| 177 | `HAPTIC_FEEDBACK` | decoded only | no haptics |
| 191 | `WAKE_IN` | supported | how long the host may wait before drawing again, through `RemoteComposeDocument.nextRepaintDelayMillis`; the soonest request wins once one has been served |
| 179 | `DEBUG_MESSAGE` | decoded only | nothing is logged |
| 185 | `REM` | decoded only | a comment record |
| 241 | `SKIP` | supported |  |

### Data pools

| Id | Opcode | Status | Notes |
| --: | --- | --- | --- |
| 170 | `ATTRIBUTE_TEXT` | partial | a measurement of the text, or its length; the monospace and max-height measuring flags are not applied |
| 239 | `CORE_TEXT` | partial | drawn with its text, colour, size, weight and alignment, taking from a `TEXT_STYLE` what it did not state, and broken into lines: `maxLines`, the three ellipsis overflows, the two line-height parameters and inter-word justification. `BREAK_STRATEGY_HIGH_QUALITY` and `_BALANCED` fall back to the greedy break, `JUSTIFICATION_MODE_INTER_CHARACTER` to inter-word, and letter spacing and hyphenation are read and not applied |
| 242 | `TEXT_STYLE` | supported | a bundle of text parameters under an id, for a `CORE_TEXT` to point at |
| 184 | `DRAW_BITMAP_TEXT_ANCHORED` | supported | a bitmap-font run placed by pan about a point, as `DRAW_TEXT_ANCHOR` places ordinary text |
| 172 | `ATTRIBUTE_TIME` | partial | the clock and calendar parts of a moment, and the gap between two; in UTC, since the real clock's zone is not in the jars |
| 171 | `ATTRIBUTE_IMAGE` | supported | the width or height `DATA_BITMAP` declared |
| 210 | `HOST_NAMED_ACTION` | supported | an action named by a string, carrying a float, int, string or float list |
| 216 | `HOST_METADATA_ACTION` | supported | a numbered action with a string beside it |
| 137 | `NAMED_VARIABLE` | supported | gives a pool value a name, and `RemoteComposeDocument.setNamed*` is the host putting one in by it |
| 189 | `DATA_FONT` | supported | the font file the paint's `TYPEFACE` names by id, in place of a built-in family. Weight and slant describe the file rather than restyle it, which is what the official player does with them. On Android the platform reads the file from API 29; older devices keep the family the paint had |
| 45 | `DATA_SHADER` | supported | the source is compiled and painted with, through Skia's runtime effect on iOS and desktop and `RuntimeShader` on Android, where it needs API 33. Float and int uniforms are set; bitmap uniforms are not |
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
| 81 | `ANIMATED_FLOAT` | partial | cubic, bounce, elastic and spline easing, and wrap-around so a value on a circle goes the short way; directional snap is decoded only. Collection operators read a list (`A_DEREF`, `A_MAX`, `A_MIN`, `A_SUM`, `A_AVG`, `A_LEN`); `A_SPLINE` and the rest are not run |
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
| 150 | `COMPONENT_VALUE` | supported | one measurement of a named component — its size, its place, its place in the window or its content size — published under a float id. `Component.updateVariables` runs before the frame is measured, so the value is the previous layout's and a document reading its own size settles on the next frame |
| 142 | `REFERENCED_OPERATIONS` | supported | a block kept under an id rather than run where it is written; the document collects every one as it loads, so an include may name a block written after it |
| 245 | `INCLUDE_REFERENCED_OPERATIONS` | supported | puts the block here, with the remap context forked so each inclusion declares its own ids |
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
| 176 | `LAYOUT_FIT_BOX` | partial | the first child that fits is shown and the rest are hidden, so a document can carry several versions of one thing and let the room choose — nothing is scaled, the name being about choosing. What a version needs is the minimum of its `MODIFIER_WIDTH_IN`/`MODIFIER_HEIGHT_IN`, as `computeSizeOriginal` compares. `computeSizePriorityFix`, the branch a document takes unless it turns feature 23 off, tests `minIntrinsicWidth` first; that reads what an earlier measure pass left and this renderer measures once, so it is not applied |
| 200 | `LAYOUT_ROOT` | supported |  |
| 201 | `LAYOUT_CONTENT` | supported |  |
| 202 | `LAYOUT_BOX` | supported |  |
| 203 | `LAYOUT_ROW` | supported |  |
| 204 | `LAYOUT_COLUMN` | supported |  |
| 205 | `LAYOUT_CANVAS` | supported |  |
| 207 | `LAYOUT_CANVAS_CONTENT` | supported |  |
| 208 | `LAYOUT_TEXT` | supported | broken into lines the same way `CORE_TEXT` is; `computeWrapSize` passes no line-height or justification of its own, only the alignment, the overflow and the line limit |
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
| 224 | `MODIFIER_GRAPHICS_LAYER` | partial | alpha, scale, translation, transform origin, shape clip and all three rotations, each read as a value the document may compute per frame rather than a written constant; `ROTATION_X`/`ROTATION_Y` foreshorten without perspective, so `CAMERA_DISTANCE` has no effect. `SHADOW_ELEVATION` is cast from the component's outline, which is what the official Compose player does with it. `TRANSLATION_Z`, `SPOT_SHADOW_COLOR`, `AMBIENT_SHADOW_COLOR`, `COMPOSITING_STRATEGY`, `HAS_BLUR`, `BLUR_RADIUS_X`, `BLUR_RADIUS_Y` and `BLUR_TILE_MODE` are decoded and ignored, because the official Compose player does not act on them either: with no reference behaviour to compare against, anything this renderer did with them would be its own invention. `CAMERA_DISTANCE` is the one the official player does act on and this does not |
| 226 | `MODIFIER_SCROLL` | supported | the window, the offset, dragging, the glide after a release and the notch it settles on; a press outside the component leaves it alone |
| 228 | `MODIFIER_MARQUEE` | partial | content wider than its component sweeps across it and back, by the raised sine `paint` uses, after the initial delay. The iteration count, the animation mode and the repeat delay are decoded and go unused in `paint` upstream too |
| 229 | `MODIFIER_RIPPLE` | supported | the library's own near-white circle, which shows on a coloured surface and barely on a white one |
| 231 | `MODIFIER_WIDTH_IN` | supported |  |
| 232 | `MODIFIER_HEIGHT_IN` | supported |  |
| 235 | `MODIFIER_COLLAPSIBLE_PRIORITY` | supported |  |
| 237 | `MODIFIER_ALIGN_BY` | decoded only | baseline alignment is not applied |
| 238 | `LAYOUT_COMPUTE` | partial | `[x, y, width, height, parentWidth, parentHeight]` go into the block's float list, the block runs, and the width and height (`TYPE_MEASURE`) or the x and y (`TYPE_POSITION`) are read back. The parent's size given to a `TYPE_MEASURE` block is the room the parent offered rather than its own measure, which this renderer does not have while a child is being measured; the animate-changes flag is decoded and not applied |
| 243 | `MODIFIER_DIMENSION_CONSTRAINTS` | supported |  |

### Touch and actions

| Id | Opcode | Status | Notes |
| --: | --- | --- | --- |
| 59 | `MODIFIER_CLICK` | supported |  |
| 64 | `CLICK_AREA` | supported |  |
| 83 | `MODIFIER_MULTI_CLICK` | supported |  |
| 157 | `TOUCH_EXPRESSION` | partial | default drag mode, and every stop mode after a release: `STOP_GENTLY`, `STOP_ENDS`, `STOP_INSTANTLY`, `STOP_ABSOLUTE_POS` and the four notch modes. Wrap mode is applied where a released value settles but not to the value itself, which is clamped rather than carried round |
| 209 | `HOST_ACTION` | supported |  |
| 212 | `VALUE_INTEGER_CHANGE_ACTION` | supported |  |
| 213 | `VALUE_STRING_CHANGE_ACTION` | supported |  |
| 218 | `VALUE_INTEGER_EXPRESSION_CHANGE_ACTION` | supported |  |
| 219 | `MODIFIER_TOUCH_DOWN` | supported |  |
| 220 | `MODIFIER_TOUCH_UP` | supported |  |
| 222 | `VALUE_FLOAT_CHANGE_ACTION` | supported |  |
| 225 | `MODIFIER_TOUCH_CANCEL` | supported |  |
| 227 | `VALUE_FLOAT_EXPRESSION_CHANGE_ACTION` | supported |  |
| 236 | `RUN_ACTION` | supported | a block of actions run every time the component it modifies is painted — `isDirty` is hardcoded true upstream and `markNotDirty` does nothing — and not at all for a component that is not painted |


## Not decoded

A document using any of these fails to parse. They fall into groups: sound (`PLAY_SOUND`,
`DATA_SOUND`, `SOUND_EXPRESSION`), the rest of the loom system (`IMPULSE_START`,
`IMPULSE_PROCESS`, `PARTICLE_PROCESS`), accessibility semantics, and the extension range.

Five of them have no class in remote-core 1.0.0-alpha18 at all — the constant is declared in
`Operations` and nothing answers to it, so there is nothing to decode until upstream implements
them: `LOAD_BITMAP` (4), `MATRIX_SET` (132), `PARTICLE_PROCESS` (162), `UPDATE` (195) and
`ACCESSIBILITY_SEMANTICS` (250), the last being an interface rather than an operation.

| Id | Opcode |
| --: | --- |
| 2 | `COMPONENT_START` |
| 4 | `LOAD_BITMAP` |
| 132 | `MATRIX_SET` |
| 139 | `DRAW_CONTENT` |
| 141 | `PLAY_SOUND` |
| 162 | `PARTICLE_PROCESS` |
| 164 | `IMPULSE_START` |
| 165 | `IMPULSE_PROCESS` |
| 169 | `DATA_SOUND` |
| 190 | `DRAW_TO_BITMAP` |
| 195 | `UPDATE` |
| 206 | `SOUND_EXPRESSION` |
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
