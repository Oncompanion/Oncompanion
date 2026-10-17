// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebLinkTest {

  @Test
  fun webAddresses_areKept() {
    assertEquals("https://example.org/yoga", webLinkOrNull("https://example.org/yoga"))
    assertEquals("http://example.org", webLinkOrNull("http://example.org"))
    assertEquals("HTTPS://example.org", webLinkOrNull("HTTPS://example.org"))
  }

  @Test
  fun surroundingSpaces_areRemoved() {
    assertEquals("https://example.org", webLinkOrNull("  https://example.org\n"))
  }

  @Test
  fun missingOrBlankLinks_giveNull() {
    assertNull(webLinkOrNull(null))
    assertNull(webLinkOrNull(""))
    assertNull(webLinkOrNull("   "))
  }

  @Test
  fun linksThatAreNotWebAddresses_giveNull() {
    assertNull(webLinkOrNull("example.org/yoga")) // no scheme
    assertNull(webLinkOrNull("https://")) // no host
    assertNull(webLinkOrNull("javascript:alert(1)"))
    assertNull(webLinkOrNull("intent://scan/#Intent;scheme=zxing;end"))
    assertNull(webLinkOrNull("tel:+41220000000"))
    assertNull(webLinkOrNull("ftp://example.org"))
  }

  @Test
  fun malformedLinks_giveNull() {
    assertNull(webLinkOrNull("https://exa mple.org"))
  }
}
