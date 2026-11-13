package org.thoughtcrime.securesms.stickers;

import android.app.Application;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class)
public class StickerUrlTest {
  private static final String PREFIX = "https://signal.art/addstickers/#pack_id=";

  @Test public void validShareLinksPreserveHexCase() {
    String url = PREFIX + "aB01&pack_key=Ff20";
    assertTrue(StickerUrl.isValidShareLink(url));
    assertEquals("aB01", StickerUrl.parseShareLink(url).get().getFirst());
    assertEquals("Ff20", StickerUrl.parseShareLink(url).get().getSecond());
  }

  @Test public void rejectsMalformedShareLinks() {
    String[] invalid = { null, "", "http://signal.art/addstickers/#pack_id=aa&pack_key=bb",
        "https://other.art/addstickers/#pack_id=aa&pack_key=bb", PREFIX + "&pack_key=bb",
        PREFIX + "aa&pack_key=", PREFIX + "a&pack_key=bb", PREFIX + "aa&pack_key=b",
        PREFIX + "zz&pack_key=bb", PREFIX + "aa&pack_key=gg", PREFIX + "aa&pack_key=bb&pack_key=cc",
        PREFIX + "aa&pack_key=bb\n!" };
    for (String url : invalid) assertFalse(String.valueOf(url), StickerUrl.isValidShareLink(url));
  }

  @Test public void rejectsManyRepeatedParameters() {
    StringBuilder url = new StringBuilder(PREFIX).append("aa");
    for (int i = 0; i < 4000; i++) url.append("&pack_key=aa");
    url.append("\n!");
    assertFalse(StickerUrl.isValidShareLink(url.toString()));
  }

  @Test public void actionParametersRequireNonemptyEvenHex() {
    assertFalse(StickerUrl.parseActionUri(null).isPresent());
    assertTrue(StickerUrl.parseActionUri(Uri.parse("sgnl://addstickers?pack_id=Ab01&pack_key=Ff20")).isPresent());
    String[] invalid = { "", "a", "zz", "aa&pack_key=gg" };
    for (String id : invalid) {
      assertFalse(id, StickerUrl.parseActionUri(Uri.parse("sgnl://addstickers?pack_id=" + id + "&pack_key=bb")).isPresent());
    }
  }
}
