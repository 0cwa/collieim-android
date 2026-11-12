# Symmetric hero divider clearance

Implementation source:531cc156584569c8b40cdec2bbadbfbcf71dae9b. Only hero bottom padding changes. The pseudo-element arc rule, all HTML/copy/native assets and all other CSS are unchanged.

Measurements compare the same1100px-high source-render viewport at each width, with the montage scrolled to the same80px stage position. Clearance is maximum transformed .phone bottom to the conservative highest point of the arc (its center apex). Rotated rear bounds in separate page-top captures can differ by about1px from scroll rounding; the matched before/after comparison below uses the same viewport/scroll setup.

|Width|Old clearance|New clearance|Arc/intro shift|New intro heading documentY|
|---|---:|---:|---:|---:|
|320|21.48|85.48|64|1044.39|
|390|21.07|85.07|64|1066.50|
|768|22.55|102.55|80|1104.80|
|1280|44.31|124.31|80|928.73|

Arc computed width/height/radii/stroke/left/right/bottom match exactly at each width. Headline/lede/actions/stage/front-image rectangles match exactly in the matched comparisons. First fully readable chat-text pageY remains649.92/615.70/569.19/277.32 at320/390/768/1280.

Fourteen website tests and git diff --check pass. No asset/native/main changes or metadata subsystem change. Current light full-width40px bands after the arc are uniformly RGB249/251/244 at320/390/768/1280 (ffmpeg crop→rgb24 channel min=max), with no shadow spill. Browser anchor probes put every narrow heading below the56px header; minimum observed87.77px. These are DOM anchor/readiness probes, not a new successful trusted keyboard-input run.

## desktop-light.png (1280×960,light)

```text
ready {"width":1280,"height":960,"usable":1280,"fonts":"loaded","images":[true,true,true,true],"overflow":false,"dark":false}
dividerGeometry {"heroBottom":851.9375,"arcApex":776.1406,"phoneBottom":652.8336791992188,"clearance":123.3069208007812,"introTop":851.9375,"introHeading":928.734375,"headerBottom":89.71875,"arc":{"width":"1408px","height":"76.7969px","left":"-64px","right":"-64px","bottom":"-1px","radius":"50% 50% 0px 0px / 100% 100% 0px 0px","borderWidth":"3px"},"creamStripViewportY":854}
sectionClearance [{"id":"about","headingTop":100.734375,"headerBottom":-738.28125},{"id":"features","headingTop":87.875,"headerBottom":-1159.28125},{"id":"upcoming","headingTop":71.71875,"headerBottom":-2117.28125},{"id":"community","headingTop":71.796875,"headerBottom":-2827.28125},{"id":"get","headingTop":136.328125,"headerBottom":-3525.28125}]
```

## desktop-dark.png (1280×960,dark)

```text
ready {"width":1280,"height":960,"usable":1280,"fonts":"loaded","images":[true,true,true,true],"overflow":false,"dark":true}
dividerGeometry {"heroBottom":851.9375,"arcApex":776.1406,"phoneBottom":652.8336791992188,"clearance":123.3069208007812,"introTop":851.9375,"introHeading":928.734375,"headerBottom":89.71875,"arc":{"width":"1408px","height":"76.7969px","left":"-64px","right":"-64px","bottom":"-1px","radius":"50% 50% 0px 0px / 100% 100% 0px 0px","borderWidth":"3px"},"creamStripViewportY":854}
sectionClearance [{"id":"about","headingTop":100.734375,"headerBottom":-738.28125},{"id":"features","headingTop":87.875,"headerBottom":-1159.28125},{"id":"upcoming","headingTop":71.71875,"headerBottom":-2117.28125},{"id":"community","headingTop":71.796875,"headerBottom":-2827.28125},{"id":"get","headingTop":136.328125,"headerBottom":-3525.28125}]
```

## mobile-light.png (390×844,light)

```text
ready {"width":390,"height":844,"usable":390,"fonts":"loaded","images":[true,true,true,true],"overflow":false,"dark":false}
dividerGeometry {"heroBottom":1018.5,"arcApex":979.5,"phoneBottom":894.4294357299805,"clearance":85.07056427001953,"introTop":1018.5,"introHeading":1066.5,"headerBottom":56,"arc":{"width":"429px","height":"40px","left":"-19.5px","right":"-19.5px","bottom":"-1px","radius":"50% 50% 0px 0px / 100% 100% 0px 0px","borderWidth":"3px"},"creamStripViewportY":569}
sectionClearance [{"id":"about","headingTop":103.5,"headerBottom":56},{"id":"features","headingTop":88.46875,"headerBottom":56},{"id":"upcoming","headingTop":111.625,"headerBottom":56},{"id":"community","headingTop":87.765625,"headerBottom":56},{"id":"get","headingTop":124.078125,"headerBottom":56}]
```

## mobile-dark.png (390×844,dark)

```text
ready {"width":390,"height":844,"usable":390,"fonts":"loaded","images":[true,true,true,true],"overflow":false,"dark":true}
dividerGeometry {"heroBottom":1018.5,"arcApex":979.5,"phoneBottom":894.4294357299805,"clearance":85.07056427001953,"introTop":1018.5,"introHeading":1066.5,"headerBottom":56,"arc":{"width":"429px","height":"40px","left":"-19.5px","right":"-19.5px","bottom":"-1px","radius":"50% 50% 0px 0px / 100% 100% 0px 0px","borderWidth":"3px"},"creamStripViewportY":569}
sectionClearance [{"id":"about","headingTop":103.5,"headerBottom":56},{"id":"features","headingTop":88.46875,"headerBottom":56},{"id":"upcoming","headingTop":111.625,"headerBottom":56},{"id":"community","headingTop":87.765625,"headerBottom":56},{"id":"get","headingTop":124.078125,"headerBottom":56}]
```

## hero-narrow-light.png (320×844,light)

```text
ready {"width":320,"height":844,"usable":320,"fonts":"loaded","images":[true,true,true,true],"overflow":false,"dark":false}
dividerGeometry {"heroBottom":996.390625,"arcApex":957.390625,"phoneBottom":871.9121398925781,"clearance":85.47848510742188,"introTop":996.390625,"introHeading":1044.390625,"headerBottom":56,"arc":{"width":"352px","height":"40px","left":"-16px","right":"-16px","bottom":"-1px","radius":"50% 50% 0px 0px / 100% 100% 0px 0px","borderWidth":"3px"},"creamStripViewportY":497}
sectionClearance [{"id":"about","headingTop":104.390625,"headerBottom":56},{"id":"features","headingTop":88.15625,"headerBottom":56},{"id":"upcoming","headingTop":111.828125,"headerBottom":56},{"id":"community","headingTop":87.765625,"headerBottom":56},{"id":"get","headingTop":123.671875,"headerBottom":56}]
```

## page-top-tablet-light.png (768×1200,light)

```text
ready {"width":768,"height":1200,"usable":768,"fonts":"loaded","images":[true,true,true,true],"overflow":false,"dark":false}
dividerGeometry {"heroBottom":1056.796875,"arcApex":1011.718775,"phoneBottom":910.16796875,"clearance":101.55080625000005,"introTop":1056.796875,"introHeading":1104.796875,"headerBottom":56,"arc":{"width":"844.781px","height":"46.0781px","left":"-38.3906px","right":"-38.3906px","bottom":"-1px","radius":"50% 50% 0px 0px / 100% 100% 0px 0px","borderWidth":"3px"},"creamStripViewportY":1059}
sectionClearance [{"id":"about","headingTop":103.796875,"headerBottom":56},{"id":"features","headingTop":94.5625,"headerBottom":56},{"id":"upcoming","headingTop":111.984375,"headerBottom":56},{"id":"community","headingTop":88.359375,"headerBottom":56},{"id":"get","headingTop":349.4375,"headerBottom":56}]
```
