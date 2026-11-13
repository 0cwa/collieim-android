# Community mural and text alignment

The user requested the mural behind “Help shape Collie”, then asked to lower the initial placement and align Help with Matrix/forum text. The existing Community pseudo-element now anchors the proportionally sized unchanged artwork48px from the section top on desktop,128px through1100px,140px through600px. This clears the main heading/intro on phones and places the face near upper Matrix content. Desktop lowers32px from its earlier position. No image-size, opacity, blend, copy or SVG change.

Help uses the same60px text inset (4px inset +40px icon +16px gap) and16px grid gap as Matrix/forum. Both grids stack through860px. Actual native text x positions:

|Width|Matrix / Help heading, bug body/action|Forum / donation body/action|
|---|---|---|
|1280|160|708|
|768|90.71875|90.71875|
|390|76|76|
|320|76|76|

[Desktop light](community-1280-light.png) · [desktop dark](community-1280-dark.png) · [phone light](community-390-light.png) · [phone dark](community-390-dark.png). Exact-built-source native browser renders, ready fonts/images. External build/diffcheck pass; no overflow at320/390/768/1280. All copy, mural SVG bytes, divider and import configuration remain unchanged. Canonical SHA f5f041c9feb0d6275fd3d0ade6f4a383da52e256850ec8713bf0bdcf00e6c159; dark SHA a2491f5582668952b0270692f47739427fec644fd8e53befd144e1dc780e47fa. Focused CSS placement/alignment only; unrelated suite not repeated.
