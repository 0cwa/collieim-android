package org.signal.storage.keyvalue

class OnboardingValues(store: KeyValueStore) : SignalStoreValues(store) {

  companion object {
    private const val SHOW_STORIES = "onboarding.stories_molly"
    private const val SHOW_APPEARANCE = "onboarding.appearance"
    private const val SHOW_ADD_PHOTO = "onboarding.add_photo"
    private const val SHOW_SET_UP_USERNAME = "onboarding.set_up_username"
  }

  public override fun onFirstEverAppLaunch() {
    putBoolean(SHOW_STORIES, true)
    putBoolean(SHOW_APPEARANCE, true)
    putBoolean(SHOW_ADD_PHOTO, true)
    putBoolean(SHOW_SET_UP_USERNAME, true)
  }

  override val keysToIncludeInBackup: List<String>
    get() = emptyList()

  fun clearAll() {
    setShowStories(false)
    setShowAppearance(false)
    setShowAddPhoto(false)
    setShowSetUpUsername(false)
  }

  fun hasOnboarding(): Boolean {
    return shouldShowStories() ||
      shouldShowAppearance() ||
      shouldShowAddPhoto() ||
      shouldShowSetUpUsername()
  }

  fun setShowStories(value: Boolean) {
    putBoolean(SHOW_STORIES, value)
  }

  fun shouldShowStories(): Boolean {
    return getBoolean(SHOW_STORIES, false)
  }

  fun setShowAppearance(value: Boolean) {
    putBoolean(SHOW_APPEARANCE, value)
  }

  fun shouldShowAppearance(): Boolean {
    return getBoolean(SHOW_APPEARANCE, false)
  }

  fun setShowAddPhoto(value: Boolean) {
    putBoolean(SHOW_ADD_PHOTO, value)
  }

  fun shouldShowAddPhoto(): Boolean {
    return getBoolean(SHOW_ADD_PHOTO, false)
  }

  fun setShowSetUpUsername(value: Boolean) {
    putBoolean(SHOW_SET_UP_USERNAME, value)
  }

  fun shouldShowSetUpUsername(): Boolean {
    return getBoolean(SHOW_SET_UP_USERNAME, false)
  }
}
