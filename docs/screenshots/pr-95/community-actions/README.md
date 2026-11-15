# Community actions and Donate

Report a bug is directly below Browse the forum, retaining its GitHub Issues destination. Removed the separate Help heading and explanatory paragraphs. Donate is one restrained aligned heading, followed by GitHub Sponsors and the retained Liberapay option.

Verified GitHub public GraphQL metadata: repository fundingLinks lists GitHub and Liberapay; owner hasSponsorsListing is true; user.sponsorsListing.url is https://github.com/sponsors/0cwa. No guessed funding URL.

## Responsive composition

[320 light](community-320-light.png) · [320 dark](community-320-dark.png) · [375](community-375-light.png) · [390](community-390-light.png) · [768](community-768-light.png) · [1280](community-1280-light.png) · [phone dark](community-390-dark.png) · [desktop dark](community-1280-dark.png).

The320 mural previously crossed Matrix title/description. Only through360px, the same full-viewport-width asset anchors70px below Donate’s top, placing its face beside Sponsors and lower contours below donation text. The original viewport-edge continuity is retained, without an internal canvas cut. Existing390=140px,768=128px anddesktop=48px placements are retained: broad art behind primary community content, with main heading/intro clear. Asset size/bytes/theme opacity are unchanged; no redraw or opaque mats.

## Lower-page alignment

[Phone bottom context](bottom-390-light.png) · [desktop bottom context](bottom-1280-light.png).

Exact horizontal boxes are symmetric. Get card left/right margins:16/16 at320/390;30.71875/30.71875 at768;100/100 at1280. Its inner text has44/44 phone margins and69.109375/69.109375 at768. Desktop text occupies the intentional left column alongside the logo. Footer content shares the16px/30.71875px/100px outer content edges; its navigation is a normal wrapping flex row. Border-box sizing is already universal; no centering defect was found.

The minor spacing imbalance was narrow Donate→Get gap112px versus40px Get→footer. Reducing only Community bottom padding through860px from72px to40px makes Donate→Get80px; desktop retains96px, and Get→footer remains40px narrow/64px desktop. Donate heading/actions retain the primary60px text inset. Sponsors arrow stays with its word at320.

External build, live text/action checks and diffcheck pass; exact-source browser frames report fonts/images ready and no overflow320/390/768/1280. No unrelated suite repeated. All SVG bytes, divider, copy outside Community and install/import configuration unchanged.
