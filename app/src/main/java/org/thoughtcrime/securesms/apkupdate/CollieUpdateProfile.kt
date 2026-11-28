package org.thoughtcrime.securesms.apkupdate

/**
 * Fixed update identities, reviewed against collie-release-engineering at 68bbd51573f061b0a8d0d41d25d7c6f7f4558327:
 * distribution/channel_profile.py and tools/patches/release_artifacts.py. See docs/APP-UPDATES.md.
 *
 * Each installed package follows its own listing in the shared repository. These identities are
 * compiled into the app; neither remote metadata nor a user preference can select another one.
 */
internal enum class CollieUpdateProfile(
  val channelName: String,
  val packageName: String,
  val repositoryUrl: String,
  val repositoryCertificate: String,
  val apkCertificate: String,
  val tagPrefix: String
) {
  STABLE(
    channelName = "stable",
    packageName = "im.collie.app",
    repositoryUrl = "https://repo.collie.im/fdroid/repo",
    repositoryCertificate = "b0c5dcbac2b65ada4e8f641910097fb0bf86a5f7255319584d453c2932f5233c",
    apkCertificate = "f8cc0c0c052e5635c26fcd293c2b0af3e87d61ff7f2df3602a9af6e91f73c826",
    tagPrefix = "v"
  ),
  TRAILBLAZER_BETA(
    channelName = "trailblazer-beta",
    packageName = "im.collie.app.trailblazer",
    repositoryUrl = "https://repo.collie.im/fdroid/repo",
    repositoryCertificate = "b0c5dcbac2b65ada4e8f641910097fb0bf86a5f7255319584d453c2932f5233c",
    apkCertificate = "a515c03498bce8852b835544910b09ccc11a3abbed23e826e9c62c002680fe98",
    tagPrefix = "trailblazer-v"
  );

  fun isValidVersionName(versionName: String): Boolean {
    return when (this) {
      STABLE -> versionName.length <= 80 && STABLE_VERSION_NAME.matches(versionName)
      TRAILBLAZER_BETA -> BETA_VERSION_NAME.matches(versionName) && !DEVELOPMENT_VERSION.containsMatchIn(versionName)
    }
  }

  fun releasePage(versionName: String): String {
    require(isValidVersionName(versionName)) { "Invalid Collie release version" }
    return "https://github.com/0cwa/collieim-android/releases/tag/$tagPrefix$versionName"
  }

  companion object {
    private val STABLE_VERSION_NAME = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9]+)?(?:-(?:alpha|beta|rc)[0-9]+)?")
    private val BETA_VERSION_NAME = Regex("[0-9]+(?:\\.[0-9]+)+(?:[-+][A-Za-z0-9.+-]+)?")
    private val DEVELOPMENT_VERSION = Regex("(?:^|[-+.])(?:dev|development|unvalidated|port|snapshot|local)(?:$|[-+.])", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun forApplicationId(applicationId: String): CollieUpdateProfile? = entries.firstOrNull { it.packageName == applicationId }
  }
}
