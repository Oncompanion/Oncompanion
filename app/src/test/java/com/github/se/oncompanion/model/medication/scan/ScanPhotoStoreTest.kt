// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScanPhotoStoreTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private lateinit var cacheDir: File
  private lateinit var store: ScanPhotoStore

  private val scansDir
    get() = File(cacheDir, ScanPhotoStore.DIRECTORY)

  @Before
  fun setUp() {
    cacheDir = temporaryFolder.newFolder("cache")
    store = ScanPhotoStore(cacheDir)
  }

  /** What the camera does with a file the store gave it. */
  private fun takePhoto(): File = store.newPhotoFile().apply { writeText("jpeg") }

  @Test
  fun newPhotoFile_isAJpegInTheScansFolder_notCreatedYet() {
    val photo = store.newPhotoFile()

    assertEquals(scansDir.absoluteFile, photo.absoluteFile.parentFile)
    assertTrue(photo.name.endsWith(".jpg"))
    assertFalse(photo.exists())
  }

  @Test
  fun newPhotoFile_createsTheScansFolder() {
    assertFalse(scansDir.exists())

    store.newPhotoFile()

    assertTrue(scansDir.isDirectory)
  }

  @Test
  fun newPhotoFile_givesADifferentFileEachTime() {
    assertNotEquals(store.newPhotoFile(), store.newPhotoFile())
  }

  @Test
  fun delete_removesThePhoto() {
    val photo = takePhoto()

    assertTrue(store.delete(photo))

    assertFalse(photo.exists())
  }

  @Test
  fun delete_aPhotoThatIsAlreadyGone_doesNothing() {
    val photo = store.newPhotoFile()

    assertTrue(store.delete(photo))

    assertFalse(photo.exists())
  }

  @Test
  fun delete_thatFails_isReported() {
    // A non-empty folder can't be deleted, like a photo the system refuses to remove
    val stuck = store.newPhotoFile().apply { mkdirs() }
    File(stuck, "inside").writeText("x")

    assertFalse(store.delete(stuck))
    assertTrue(stuck.exists())
  }

  @Test
  fun delete_neverRemovesAFileOutsideTheScansFolder() {
    val other = File(cacheDir, "other.jpg").apply { writeText("not a scan") }
    val outside = temporaryFolder.newFile("elsewhere.jpg")

    assertFalse(store.delete(other))
    assertFalse(store.delete(outside))

    assertTrue(other.exists())
    assertTrue(outside.exists())
  }

  @Test
  fun delete_neverFollowsAPathOutOfTheScansFolder() {
    store.newPhotoFile()
    val secret = File(cacheDir, "secret.jpg").apply { writeText("not a scan") }
    val nested =
        File(scansDir, "sub/scan-nested.jpg").apply {
          parentFile?.mkdirs()
          writeText("jpeg")
        }
    // A plain "starts with scans" check on the path would accept this one
    val lookalike =
        File(cacheDir, "scans-other/scan-x.jpg").apply {
          parentFile?.mkdirs()
          writeText("not a scan")
        }

    assertFalse(store.delete(File(scansDir, "../secret.jpg")))
    assertFalse(store.delete(nested))
    assertFalse(store.delete(lookalike))
    assertFalse(store.delete(scansDir))
    assertFalse(store.delete(cacheDir))

    listOf(secret, nested, lookalike, scansDir, cacheDir).forEach { assertTrue(it.exists()) }
  }

  @Test
  fun clear_removesEveryPhoto_butKeepsTheRestOfTheCache() {
    val photos = listOf(takePhoto(), takePhoto())
    val other = File(cacheDir, "other.txt").apply { writeText("keep me") }

    assertTrue(store.clear())

    photos.forEach { assertFalse(it.exists()) }
    assertTrue(other.exists())
  }

  @Test
  fun clear_thatFailsOnOnePhoto_stillDeletesTheOthers_andReportsIt() {
    val stuck = store.newPhotoFile().apply { mkdirs() }
    File(stuck, "inside").writeText("x")
    val photos = listOf(takePhoto(), takePhoto())

    assertFalse(store.clear())

    photos.forEach { assertFalse(it.exists()) }
  }

  @Test
  fun clear_withoutAnyScanYet_doesNothing() {
    assertTrue(store.clear())

    assertFalse(scansDir.exists())
  }

  @Test
  fun clear_withoutACacheFolderYet_doesNothing() {
    assertTrue(ScanPhotoStore(File(cacheDir, "missing")).clear())
  }

  @Test
  fun newPhotoFile_stillWorksAfterClear() {
    takePhoto()
    store.clear()

    val next = takePhoto()

    assertTrue(next.exists())
  }
}
