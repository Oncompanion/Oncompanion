package com.github.se.oncompanion.model.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthUserTest {

  @Test
  fun defaultsAreNull() {
    val user = AuthUser(uid = "u1")
    assertEquals("u1", user.uid)
    assertNull(user.email)
    assertNull(user.displayName)
    assertNull(user.givenName)
  }

  @Test
  fun firstNameGuess_givenNameWins() {
    val user = AuthUser(uid = "u1", displayName = "Alice Martin", givenName = "Ally")
    assertEquals("Ally", user.firstNameGuess())
  }

  @Test
  fun firstNameGuess_givenNameAloneIsUsed() {
    assertEquals("Ally", AuthUser(uid = "u1", givenName = "Ally").firstNameGuess())
  }

  @Test
  fun firstNameGuess_blankGivenNameFallsBackToDisplayName() {
    val user = AuthUser(uid = "u1", displayName = "Alice Martin", givenName = "   ")
    assertEquals("Alice", user.firstNameGuess())
  }

  @Test
  fun firstNameGuess_emptyGivenNameFallsBackToDisplayName() {
    val user = AuthUser(uid = "u1", displayName = "Alice Martin", givenName = "")
    assertEquals("Alice", user.firstNameGuess())
  }

  @Test
  fun firstNameGuess_nullGivenNameUsesFirstWordOfDisplayName() {
    assertEquals("Alice", AuthUser(uid = "u1", displayName = "Alice Martin").firstNameGuess())
  }

  @Test
  fun firstNameGuess_displayNameIsTrimmed() {
    assertEquals("Alice", AuthUser(uid = "u1", displayName = "  Alice  ").firstNameGuess())
  }

  @Test
  fun firstNameGuess_paddedMultiWordDisplayName() {
    assertEquals("Alice", AuthUser(uid = "u1", displayName = "  Alice Martin  ").firstNameGuess())
  }

  @Test
  fun firstNameGuess_singleWordDisplayName() {
    assertEquals("Alice", AuthUser(uid = "u1", displayName = "Alice").firstNameGuess())
  }

  @Test
  fun firstNameGuess_allNullReturnsNull() {
    assertNull(AuthUser(uid = "u1").firstNameGuess())
  }

  @Test
  fun firstNameGuess_allBlankReturnsNull() {
    assertNull(AuthUser(uid = "u1", displayName = "   ", givenName = " ").firstNameGuess())
  }

  @Test
  fun firstNameGuess_emptyDisplayNameReturnsNull() {
    assertNull(AuthUser(uid = "u1", displayName = "", givenName = null).firstNameGuess())
  }

  @Test
  fun dataClassEqualityAndCopy() {
    val a = AuthUser("u1", "a@example.com", "Alice Martin", "Alice")
    val b = AuthUser("u1", "a@example.com", "Alice Martin", "Alice")
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
    assertNotEquals(a, a.copy(givenName = null))
  }
}
